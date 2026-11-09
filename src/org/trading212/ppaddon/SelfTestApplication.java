package org.trading212.ppaddon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;

import org.eclipse.e4.ui.model.application.MApplicationFactory;
import org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuFactory;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.FrameworkUtil;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.Extractor.InputFile;
import name.abuchen.portfolio.datatransfer.SecurityCache;
import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.datatransfer.actions.DetectDuplicatesAction;
import name.abuchen.portfolio.datatransfer.actions.InsertAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;

/** Runs only on explicit command-line application selection; never on normal GUI startup. */
public final class SelfTestApplication implements IApplication
{
    private int checks;

    @Override
    public Object start(IApplicationContext context) throws Exception
    {
        try
        {
            check("name.abuchen.portfolio.ui".equals(FrameworkUtil.getBundle(getClass()).getSymbolicName()), "fragment attached to official UI");
            var hostVersion = FrameworkUtil.getBundle(getClass()).getVersion().toString();
            check(MenuProcessor.isSupportedHost() && !MenuProcessor.supportsHost("0.89.1")
                            && !MenuProcessor.supportsHost("0.90.1"), "supported host " + hostVersion + " and exact version allowlist");
            var evidence = Path.of(System.getProperty("t212.addon.evidence"));
            Files.createDirectories(evidence);
            var app = MApplicationFactory.INSTANCE.createApplication();
            var window = MBasicFactory.INSTANCE.createWindow();
            var root = MMenuFactory.INSTANCE.createMenu();
            var imports = MMenuFactory.INSTANCE.createMenu();
            imports.setElementId("name.abuchen.portfolio.ui.menu.file.import");
            root.getChildren().add(imports);
            window.setMainMenu(root);
            app.getChildren().add(window);
            new MenuProcessor().install(app);
            new MenuProcessor().install(app);
            check(imports.getChildren().size() == 1 && app.getCommands().size() == 1 && app.getHandlers().size() == 1, "idempotent runtime menu contribution");

            var fixture = evidence.resolve("activity2026.csv");
            try (var resource = getClass().getResourceAsStream("/activity2026.csv"))
            {
                Files.copy(resource, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            var client = new Client();
            var account = new Account("Trading 212 - TEST");
            account.setCurrencyCode("EUR");
            client.addAccount(account);
            var portfolio = new Portfolio("Trading 212 - TEST");
            portfolio.setReferenceAccount(account);
            client.addPortfolio(portfolio);
            ClientFactory.save(client, evidence.resolve("wallet-empty.xml").toFile());
            var errors = new ArrayList<Exception>();
            var extractor = new Trading212CSVExtractor(client);
            var cache = new SecurityCache(client);
            var items = extractor.extract(cache, new InputFile(fixture.toFile()), errors);
            check(errors.isEmpty() && items.size() == 7, "seven synthetic transactions extracted");
            check(items.stream().anyMatch(i -> i.getSubject().getNote().contains("ABC|20260901T150000Z")), "dividend key is ticker and UTC second");
            check(items.stream().anyMatch(i -> i.getSubject().getNote().contains("XYZ|20260901T160000Z")), "ETP distribution key");
            check(extractor.extract(cache, new InputFile(fixture.toFile()), errors).isEmpty(), "same-session overlap rejected");
            var insert = new InsertAction(client);
            for (var item : items)
            {
                if (item.getSubject() instanceof BuySellEntry entry)
                    insert.process(entry, account, portfolio);
                else if (item.getSubject() instanceof AccountTransaction transaction)
                    insert.process(transaction, account);
            }
            check(account.getTransactions().size() == 7 && portfolio.getTransactions().size() == 2, "insertion through official InsertAction");
            ClientFactory.save(client, evidence.resolve("wallet-imported.xml").toFile());
            try (var input = Files.newInputStream(evidence.resolve("wallet-imported.xml")))
            {
                var loaded = ClientFactory.load(input);
                check(loaded.getAccounts().getFirst().getTransactions().size() == 7, "official wallet save/reload");
                check(loaded.getAccounts().getFirst().getTransactions().stream().anyMatch(t -> t.getNote().contains("ABC|20260901T150000Z")), "key survives save/reload in standard Note");
                checkDuplicates(fixture, loaded, "0.90.0".equals(hostVersion));
            }
            var text = Files.readString(fixture);
            var conflict = evidence.resolve("conflict.csv");
            var lines = text.lines().toList();
            Files.writeString(conflict, lines.getFirst() + "\n" + lines.get(1) + "\n" + lines.get(1).replace("200.00", "201.00") + "\n");
            errors.clear();
            var conflicting = new Trading212CSVExtractor(client).extract(new SecurityCache(client), new InputFile(conflict.toFile()), errors);
            check(errors.size() == 1 && conflicting.size() == 1 && conflicting.getFirst().isFailure(), "conflicting broker IDs block first item");
            var noid = evidence.resolve("no-id.csv");
            var payment = lines.get(5).replace("IN1", "");
            Files.writeString(noid, lines.getFirst() + "\n" + payment + "\n" + payment + "\n");
            errors.clear();
            var payments = new Trading212CSVExtractor(client).extract(new SecurityCache(client), new InputFile(noid.toFile()), errors);
            check(errors.isEmpty() && payments.size() == 2, "identical payments preserve within-file multiplicity");
            reconcilePrivate();
            Files.writeString(evidence.resolve("selftest-result.txt"), "PASS: " + checks + " checks on original Portfolio Performance " + hostVersion + ".\n");
            System.out.println("ADDON SELFTEST PASS: " + checks + " checks");
            return EXIT_OK;
        }
        catch (Exception | AssertionError failure)
        {
            failure.printStackTrace();
            return Integer.valueOf(1);
        }
    }

    private void checkDuplicates(Path fixture, Client loaded, boolean updatedDetector) throws Exception
    {
        var account = loaded.getAccounts().getFirst();
        var portfolio = loaded.getPortfolios().getFirst();
        var errors = new ArrayList<Exception>();
        var mixed = fixture.resolveSibling("mixed.csv");
        var original = Files.readString(fixture);
        Files.writeString(mixed, original.stripTrailing()
                        + "\nDeposit,2026-09-02 10:00:00+00:00,,,,Bank Transfer,DEP2,,,,,,,23.45,EUR,,\n");
        var items = new Trading212CSVExtractor(loaded).extract(new SecurityCache(loaded),
                        new InputFile(mixed.toFile()), errors);
        check(errors.isEmpty() && items.size() == 8, "mixed CSV: seven existing rows and one new row");
        var detect = new DetectDuplicatesAction(loaded);
        var insert = new InsertAction(loaded);
        int duplicates = 0;
        int added = 0;
        for (var item : items)
        {
            var subject = item.getSubject();
            var status = subject instanceof BuySellEntry entry ? detect.process(entry, account, portfolio)
                            : detect.process((AccountTransaction) subject, account);
            if (status.getCode() == ImportAction.Status.Code.WARNING)
                duplicates++;
            else if (status.getCode() == ImportAction.Status.Code.OK && subject instanceof AccountTransaction tx
                            && tx.getAmount() == 2345 && tx.getType() == AccountTransaction.Type.DEPOSIT)
            {
                insert.process(tx, account);
                added++;
            }
        }
        check(duplicates == 7 && added == 1 && account.getTransactions().size() == 8
                        && portfolio.getTransactions().size() == 2, "mixed reimport: seven flagged, only new deposit inserted");
        var expectedManualStatus = updatedDetector ? ImportAction.Status.Code.WARNING : ImportAction.Status.Code.OK;
        var dividend = account.getTransactions().stream()
                        .filter(tx -> tx.getType() == AccountTransaction.Type.DIVIDENDS).findFirst().orElseThrow();
        var manualDividend = new AccountTransaction();
        manualDividend.setType(dividend.getType());
        manualDividend.setDateTime(dividend.getDateTime().toLocalDate().atStartOfDay());
        manualDividend.setCurrencyCode(dividend.getCurrencyCode());
        manualDividend.setSecurity(dividend.getSecurity());
        manualDividend.setAmount(dividend.getAmount());
        var manualAccount = new Account();
        manualAccount.addTransaction(manualDividend);
        check(dividend.getShares() > 0 && detect.process(dividend, manualAccount).getCode() == expectedManualStatus,
                        "host-specific detection of manual dividend without share count");
        manualDividend.setAmount(dividend.getAmount() + 1);
        check(detect.process(dividend, manualAccount).getCode() == ImportAction.Status.Code.OK,
                        "dividend with a different cash amount stays eligible");

        var manualInterest = new AccountTransaction();
        manualInterest.setType(AccountTransaction.Type.INTEREST);
        manualInterest.setDateTime(dividend.getDateTime().plusDays(1));
        manualInterest.setCurrencyCode("EUR");
        manualInterest.setAmount(100);
        manualAccount.addTransaction(manualInterest);
        var netInterest = new AccountTransaction();
        netInterest.setType(AccountTransaction.Type.INTEREST);
        netInterest.setDateTime(manualInterest.getDateTime());
        netInterest.setCurrencyCode("EUR");
        netInterest.setAmount(80);
        netInterest.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 20)));
        check(detect.process(netInterest, manualAccount).getCode() == expectedManualStatus,
                        "host-specific detection of manual interest recorded gross");
    }


    private void reconcilePrivate() throws Exception
    {
        var directory = System.getenv("PP_TRADING212_PRIVATE_DIR");
        if (directory == null || directory.isBlank())
            return;
        var client = new Client();
        var cache = new SecurityCache(client);
        var extractor = new Trading212CSVExtractor(client);
        int total = 0;
        try (var paths = Files.list(Path.of(directory)))
        {
            for (var path : paths.filter(p -> p.toString().endsWith(".csv")).sorted().toList())
            {
                var expected = new Properties();
                try (var reader = Files.newBufferedReader(path.resolveSibling(path.getFileName().toString().replace(".csv", ".properties"))))
                {
                    expected.load(reader);
                }
                var errors = new ArrayList<Exception>();
                var items = extractor.extract(cache, new InputFile(path.toFile()), errors);
                check(errors.isEmpty() && items.size() == Integer.parseInt(expected.getProperty("rows")), "private row counts");
                total += items.size();
                long cash = 0;
                var shares = new HashMap<String, Long>();
                var counts = new HashMap<String, Long>();
                for (var item : items)
                {
                    if (item.getSubject() instanceof BuySellEntry entry)
                    {
                        var t = entry.getPortfolioTransaction();
                        boolean buy = t.getType() == PortfolioTransaction.Type.BUY;
                        cash += t.getAmount() * (buy ? -1 : 1);
                        shares.merge(t.getSecurity().getIsin(), t.getShares() * (buy ? 1 : -1), Long::sum);
                        counts.merge(t.getType().name(), 1L, Long::sum);
                    }
                    else if (item.getSubject() instanceof AccountTransaction t)
                    {
                        cash += t.getAmount() * (t.getType().isDebit() ? -1 : 1);
                        counts.merge(t.getType().name(), 1L, Long::sum);
                    }
                }
                check(cash == Long.parseLong(expected.getProperty("cashMinor")), "private cash totals");
                for (var key : expected.stringPropertyNames())
                {
                    if (key.startsWith("shares."))
                        check(shares.getOrDefault(key.substring(7), 0L) == Long.parseLong(expected.getProperty(key)), "private per-ISIN quantity");
                    if (key.startsWith("count."))
                        check(counts.getOrDefault(key.substring(6), 0L) == Long.parseLong(expected.getProperty(key)), "private per-type count");
                }
            }
        }
        check(total > 0, "nonempty private reconciliation");
        System.out.println("PRIVATE RECONCILIATION PASS: " + total + " rows; no private files embedded in add-on");
    }

    private void check(boolean condition, String label)
    {
        if (!condition)
            throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    @Override
    public void stop()
    {
    }
}
