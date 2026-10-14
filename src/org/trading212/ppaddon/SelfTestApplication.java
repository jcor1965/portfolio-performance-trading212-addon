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
import name.abuchen.portfolio.datatransfer.actions.InsertAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.ClientFactory;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;

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
            check(FrameworkUtil.getBundle(getClass()).getVersion().toString().equals("0.89.0"), "official 0.89.0 host");
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
            var account = new Account("Trading 212 - TESTE");
            account.setCurrencyCode("EUR");
            client.addAccount(account);
            var portfolio = new Portfolio("Trading 212 - TESTE");
            portfolio.setReferenceAccount(account);
            client.addPortfolio(portfolio);
            ClientFactory.save(client, evidence.resolve("carteira-vazia.xml").toFile());
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
            ClientFactory.save(client, evidence.resolve("carteira-importada.xml").toFile());
            try (var input = Files.newInputStream(evidence.resolve("carteira-importada.xml")))
            {
                var loaded = ClientFactory.load(input);
                check(loaded.getAccounts().getFirst().getTransactions().size() == 7, "official wallet save/reload");
                check(loaded.getAccounts().getFirst().getTransactions().stream().anyMatch(t -> t.getNote().contains("ABC|20260901T150000Z")), "key survives save/reload in standard Note");
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
            Files.writeString(evidence.resolve("selftest-result.txt"), "PASS: " + checks + " checks on original Portfolio Performance 0.89.0.\n");
            System.out.println("ADDON SELFTEST PASS: " + checks + " checks");
            return EXIT_OK;
        }
        catch (Exception | AssertionError failure)
        {
            failure.printStackTrace();
            return Integer.valueOf(1);
        }
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
