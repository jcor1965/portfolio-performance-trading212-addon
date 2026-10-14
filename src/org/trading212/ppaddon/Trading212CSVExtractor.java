package org.trading212.ppaddon;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.SecurityCache;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.Isin;

/**
 * Imports the CSV activity history exported from Trading 212 Invest.
 * Supports the 2025 (15 columns) and 2026 (17 columns) export layouts.
 * All values are taken from the broker's booked Total field; Result is a
 * realised P/L disclosure, not a separate cash transaction.
 */
@SuppressWarnings("nls")
public class Trading212CSVExtractor implements Extractor
{
    private static final Set<String> REQUIRED_HEADERS = Set.of("Action", "Time (UTC)", "Total",
                    "Currency (Total)", "ISIN", "Ticker", "Name", "No. of shares");

    // Keep state only for the duration of one wizard invocation: overlapping
    // exports selected together should not create duplicate transactions.
    private final Map<String, String> importedRows = new HashMap<>();
    private final Map<String, Item> importedItems = new HashMap<>();

    public Trading212CSVExtractor(Client client)
    {
        // The client is used by the wizard's shared SecurityCache.
    }

    @Override
    public String getLabel()
    {
        return "Trading 212 (CSV)";
    }

    @Override
    public List<Item> extract(SecurityCache securityCache, InputFile inputFile, List<Exception> errors)
    {
        var items = new ArrayList<Item>();
        var source = inputFile.getName();
        var occurrences = new HashMap<String, Integer>();

        try
        {
            var content = Files.readString(inputFile.getFile().toPath(), StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF"))
                content = content.substring(1);

            try (var reader = new StringReader(content);
                            var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                                            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                                            .setIgnoreEmptyLines(true).get().parse(reader))
            {
                if (!parser.getHeaderMap().keySet().containsAll(REQUIRED_HEADERS))
                {
                    errors.add(new IllegalArgumentException(source + ": not a Trading 212 Invest activity CSV"));
                    return items;
                }

                for (CSVRecord row : parser)
                {
                    try
                    {
                        if (!row.isConsistent())
                            throw new IllegalArgumentException("Record does not match the CSV header");
                        var signature = signature(row);
                        String key = field(row, "ID").isEmpty()
                                        ? "ROW:" + signature + ":" + occurrences.merge(signature, 1, Integer::sum)
                                        : "ID:" + field(row, "ID");
                        if (importedRows.containsKey(key))
                        {
                            if (!signature.equals(importedRows.get(key)))
                            {
                                importedItems.get(key).setFailureMessage("Conflicting rows with the same Trading 212 ID");
                                throw new IllegalArgumentException("Conflicting rows with the same Trading 212 ID");
                            }
                            continue;
                        }

                        // Only successful rows are marked as processed. An
                        // unsupported/malformed record must remain visible.
                        process(row, securityCache, source, items);
                        importedRows.put(key, signature);
                        importedItems.put(key, items.getLast());
                    }
                    catch (RuntimeException e)
                    {
                        errors.add(new IllegalArgumentException(source + ", CSV row " + row.getRecordNumber()
                                        + " (" + field(row, "Action") + "): " + e.getMessage(), e));
                    }
                }
            }
        }
        catch (IOException | UncheckedIOException | IllegalArgumentException e)
        {
            errors.add(e);
        }

        return items;
    }

    private void process(CSVRecord row, SecurityCache securities, String source, List<Item> items)
    {
        var action = field(row, "Action");
        var date = parseDate(field(row, "Time (UTC)"));
        var currency = field(row, "Currency (Total)");
        if (CurrencyUnit.getInstance(currency) == null)
            throw new IllegalArgumentException("Unsupported or missing transaction currency: " + currency);

        var total = parseMoney(field(row, "Total"));
        var note = note(row);

        // Do not silently ignore additional fees/taxes introduced in newer
        // CSV versions; they need explicit net/gross accounting rules.
        checkUnrecognisedCharges(row);

        if (action.endsWith(" buy") || action.endsWith(" sell"))
        {
            if (!Set.of("Limit buy", "Market buy", "Stop buy", "Stop limit buy", "Limit sell",
                            "Market sell", "Stop sell", "Stop limit sell").contains(action))
                throw new IllegalArgumentException("Unsupported trade action: " + action);

            var shares = positiveShares(row);
            if (total <= 0)
                throw new IllegalArgumentException("Trade Total must be positive");
            var security = security(row, currency, securities);

            var entry = new BuySellEntry();
            entry.setType(action.endsWith(" buy") ? PortfolioTransaction.Type.BUY : PortfolioTransaction.Type.SELL);
            entry.setDate(date);
            entry.setSecurity(security);
            entry.setShares(shares);
            entry.setCurrencyCode(currency);
            entry.setAmount(total);
            entry.setNote(note);
            entry.setSource(source);
            items.add(new BuySellEntryItem(entry));
            return;
        }

        var transaction = new AccountTransaction();
        switch (action)
        {
            case "Deposit":
                if (total <= 0)
                    throw new IllegalArgumentException("Deposit Total must be positive");
                transaction.setType(AccountTransaction.Type.DEPOSIT);
                break;
            case "Withdrawal":
                if (total == 0)
                    throw new IllegalArgumentException("Withdrawal Total must not be zero");
                transaction.setType(AccountTransaction.Type.REMOVAL);
                break;
            case "Interest on cash":
                transaction.setType(total >= 0 ? AccountTransaction.Type.INTEREST
                                : AccountTransaction.Type.INTEREST_CHARGE);
                break;
            case "Dividend (Dividend)":
            case "Dividend (Interest)":
                if (total < 0)
                    throw new IllegalArgumentException("Negative dividend/correction requires manual review");
                transaction.setType(AccountTransaction.Type.DIVIDENDS);
                var tax = parseMoneyOptional(field(row, "Withholding tax"));
                if (tax < 0)
                    throw new IllegalArgumentException("Negative withholding tax requires manual review");
                var taxCurrency = field(row, "Currency (Withholding tax)");
                if (tax != 0 && !currency.equals(taxCurrency))
                    throw new IllegalArgumentException("Withholding tax in a different currency requires FX handling");
                transaction.setShares(optionalShares(row));
                transaction.setSecurity(security(row, currency, securities));
                break;
            default:
                // Do not mis-classify unrecognised actions as deposits.
                throw new IllegalArgumentException("Unsupported Trading 212 action: " + action);
        }

        transaction.setDateTime(date);
        transaction.setCurrencyCode(currency);
        transaction.setAmount(Math.abs(total));
        transaction.setNote(note);
        transaction.setSource(source);
        if (action.startsWith("Dividend ("))
        {
            var tax = Math.abs(parseMoneyOptional(field(row, "Withholding tax")));
            if (tax != 0)
                transaction.addUnit(new Unit(Unit.Type.TAX, Money.of(currency, tax)));
        }
        items.add(new TransactionItem(transaction));
    }

    private Security security(CSVRecord row, String accountCurrency, SecurityCache securities)
    {
        var isin = field(row, "ISIN");
        var ticker = field(row, "Ticker");
        var name = field(row, "Name");
        var priceCurrency = field(row, "Currency (Price / share)");
        if (!isin.matches(Isin.PATTERN) || !Isin.isValid(isin) || name.isEmpty())
            throw new IllegalArgumentException("Valid ISIN and security name are required");

        // Never fabricate FX or gross-value units. This importer needs a
        // separate, tested FX extension before accepting multi-currency rows.
        if (!priceCurrency.isEmpty() && !priceCurrency.equals(accountCurrency))
            throw new IllegalArgumentException("Different price/total currencies are not yet supported: "
                            + priceCurrency + "/" + accountCurrency);

        var security = securities.lookup(isin, ticker.isEmpty() ? null : ticker, null, name,
                        () -> new Security(name, accountCurrency));
        // SecurityCache may match a ticker before checking the name. Never
        // silently merge two different ISINs that share a ticker.
        if (!isin.equals(security.getIsin()))
            throw new IllegalArgumentException("Matched security has a different or missing ISIN");
        if (!accountCurrency.equals(security.getCurrencyCode()))
            throw new IllegalArgumentException("Security/account currencies differ: " + isin);
        return security;
    }

    private void checkUnrecognisedCharges(CSVRecord row)
    {
        for (String column : row.toMap().keySet())
        {
            var lower = column.toLowerCase(java.util.Locale.ROOT);
            if ((lower.contains("fee") || lower.contains("tax") || lower.contains("charge") || lower.contains("duty")
                            || lower.contains("commission"))
                            && !column.equals("Withholding tax")
                            && !column.startsWith("Currency (") && !field(row, column).isEmpty()
                            && new BigDecimal(field(row, column)).signum() != 0)
                throw new IllegalArgumentException("Non-zero additional charge requires mapping: " + column);
        }
        if (!field(row, "Action").startsWith("Dividend (")
                        && !field(row, "Withholding tax").isEmpty()
                        && new BigDecimal(field(row, "Withholding tax")).signum() != 0)
            throw new IllegalArgumentException("Withholding tax is only supported on dividends");
    }

    private String signature(CSVRecord row)
    {
        // Ignore absent optional columns and column order across export layouts.
        // Preserve multiplicity for rows without IDs: identical legitimate
        // payments within one export must not be silently discarded.
        var fields = new TreeMap<String, String>();
        row.toMap().forEach((key, value) -> {
            if (value != null && !value.trim().isEmpty())
                fields.put(key, value.trim());
        });
        var signature = new StringBuilder();
        fields.forEach((key, value) -> signature.append(key.length()).append(':').append(key)
                        .append(value.length()).append(':').append(value));
        return signature.toString();
    }

    private String note(CSVRecord row)
    {
        var notes = field(row, "Notes");
        var id = field(row, "ID");
        var prefix = field(row, "Action");
        if (!notes.isEmpty())
            prefix += " | " + notes;
        if (!id.isEmpty())
            prefix += " | Trading 212 ID: " + id;
        else if (Set.of("Dividend (Dividend)", "Dividend (Interest)").contains(field(row, "Action")))
        {
            var ticker = field(row, "Ticker");
            if (ticker.isEmpty())
                throw new IllegalArgumentException("Ticker is required for a dividend without a broker ID");
            var timestamp = parseDate(field(row, "Time (UTC)")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            prefix += " | Trading 212 key: " + ticker + "|" + timestamp;
        }
        return prefix;
    }

    private LocalDateTime parseDate(String value)
    {
        // Trading 212 labels the time as UTC; always normalise the exported
        // offset rather than interpreting it in the computer's timezone.
        return OffsetDateTime.parse(value.replace(' ', 'T')).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private long positiveShares(CSVRecord row)
    {
        var shares = optionalShares(row);
        if (shares <= 0)
            throw new IllegalArgumentException("Trade quantity must be positive");
        return shares;
    }

    private long optionalShares(CSVRecord row)
    {
        var value = field(row, "No. of shares");
        var shares = value.isEmpty() ? 0 : parseScaled(value, Values.Share.factor(), true);
        if (shares < 0)
            throw new IllegalArgumentException("Negative shares require manual review");
        return shares;
    }

    private long parseMoneyOptional(String value)
    {
        return value.isEmpty() ? 0 : parseMoney(value);
    }

    private long parseMoney(String value)
    {
        return parseScaled(value, Values.Amount.factor(), false);
    }

    private long parseScaled(String value, long factor, boolean rejectPrecisionLoss)
    {
        var exact = new BigDecimal(value).multiply(BigDecimal.valueOf(factor));
        if (rejectPrecisionLoss && exact.stripTrailingZeros().scale() > 0)
            throw new IllegalArgumentException("Share precision exceeds Portfolio Performance support: " + value);
        return exact.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private String field(CSVRecord row, String column)
    {
        if (!row.isMapped(column) || !row.isSet(column))
            return "";
        return row.get(column).trim();
    }
}
