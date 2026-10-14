# Trading 212 CSV add-on for Portfolio Performance

An independent, experimental add-on that adds **File → Import → Trading 212 (CSV - add-on)** to the official Portfolio Performance application. Maintained by **jcor1965**. This project is not affiliated with or endorsed by Trading 212 or Portfolio Performance.

**Supported host: official Portfolio Performance 0.89.0, Windows x86_64, Java 21.** Other host versions are deliberately refused by the installer. This is a prerelease, not a stable third-party extension API.

## Install and remove

Extract the installer release ZIP, close Portfolio Performance and run `Instalar.cmd`. Select the official `PortfolioPerformance.exe`. Open the program using your usual shortcut and open a wallet to enable the menu.

To remove the add-on, close the program and run `Desinstalar.cmd`. `Verificar.cmd` reports registration and compatibility. See [Portuguese installation instructions](installer/LEIA-ME.txt).

The installer adds its JAR under `addons/trading212` and changes only `configuration/config.ini` and `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` among the shipped application files. Executables and official libraries are not patched. It backs up configuration, archives derived Eclipse caches and removes only the add-on's three persisted UI nodes on uninstall, preserving the other workspace bytes. Wallets are not modified by installation or removal; importing and saving a wallet does modify that wallet.

**Uninstall before upgrading Portfolio Performance.** Eclipse p2 may regenerate the bundle configuration. Later host versions need separate validation; this package does not automatically support them. See [compatibility and updates](docs/COMPATIBILITY.md).

## Import support and limits

- Purchases, sales, deposits, withdrawals, cash interest and dividends, including `Dividend (Interest)` ETP distributions.
- ISIN validation, up to eight decimal places for quantities, dividend withholding tax in the same currency.
- Broker IDs and `Ticker|yyyyMMddTHHmmssZ` keys for dividends without IDs are stored in the existing Note field. No wallet schema extensions.
- Additional nonzero charges and cross-currency conversion are rejected for review. Unsupported actions are reported, not silently ignored.
- Existing transactions are checked by Portfolio Performance's generic potential-duplicate detector and excluded by default. Warnings can be overridden manually. This is not a persistent broker-ID index: distinct same-day transactions with equal economic fields can be flagged. There is additional within-batch ID/conflict checking.
- The reused review window still has the generic PDF import title, while its content identifies Trading 212 CSV.

Use a **copy of your wallet** for initial testing. Choose the intended cash and securities accounts explicitly.

## Build and test

Requirements: Windows, JDK 21, an extracted official Portfolio Performance 0.89.0 distribution; Python 3 for installer regression tests. The application and JDK are not redistributed.

```powershell
./scripts/Build.ps1 -PortfolioPath 'C:\path\portfolio' -JavaHome 'C:\path\jdk-21'
./scripts/Test.ps1 -PortfolioPath 'C:\path\portfolio' -JavaHome 'C:\path\jdk-21'
python tests/test_installer.py --portfolio 'C:\path\portfolio' --java-home 'C:\path\jdk-21'
```

Build output is written to `dist/`. Tests use isolated copies/configuration under `.build/`; they never install into the supplied original application directory. Only synthetic fixtures are tracked. GitHub Actions runs the synthetic tests and installer regressions; local evidence is recorded in [validation](docs/VALIDATION.md).

## Reporting issues

Include the application version, add-on version, operation and redacted error text. **Do not upload a real wallet, original broker CSV, login details or account identifiers.** Reproduce with synthetic data wherever possible. See [contribution guidance](CONTRIBUTING.md).

## License and provenance

Eclipse Public License 1.0, matching the upstream-derived handler and package license. See [LICENSE.txt](LICENSE.txt) and [NOTICE](NOTICE). Official Portfolio Performance/Eclipse/Apache libraries are used from the installed host and are not included in the add-on binary.
