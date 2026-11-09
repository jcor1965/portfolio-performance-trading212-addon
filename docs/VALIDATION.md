# Validation of 0.1.1

Build one installer against the official 0.89.0 Windows x86_64 distribution with JDK 21. Verify both official host archives against their published SHA-256 hashes. Run the synthetic import and installer suites with the same package on each host.

The synthetic overlap test saves and reloads a wallet, then imports a CSV containing seven existing movements and one new deposit. The checks also cover the differences between the 0.89.0 and 0.90.0 duplicate detectors for manually entered dividends and interest. Host update behavior is simulated.

# Validation of 0.1.0

Local tests used official Portfolio Performance 0.89.0 on Windows x86_64 and JDK 21. The official application distribution was verified against its release SHA-256. Executables and official libraries were unchanged by the installer.

- Synthetic OSGi self-test: 12 checks covering host attachment, idempotent menu contribution, seven transaction types/examples, dividend keys, batch overlap, conflicting IDs, multiplicity, official insertion and wallet save/reload.
- Installer regression: 14 assertions, including configuration-only changes, idempotence, original-configuration OSGi loading, byte-for-byte restoration, preserved update settings, compatibility refusal and targeted persisted menu cleanup.
- Visual test: menu present on normal executable startup after installation; absent on normal startup after removal. A stale menu persisted in the UI model during development; removal now backs up and removes only the three add-on nodes and archives derived configuration caches.
- Private local reconciliation: 1,065 broker rows matched counts, cash and per-ISIN quantities. Comparing two user-produced wallets found no differences in the 1,065 transaction economic fields. These datasets are not published.
- Private mixed incremental test: 1,065 existing rows excluded by default and six synthetic new rows inserted; after save/reload, all 1,071 were potential duplicates. Existing transaction records remained unchanged. This used the official validation, selection and insertion components, not another complete visual wizard run.

The public automated checks use synthetic data only; private checks are additional local evidence, not reproducible public fixtures. This is not a claim that the entire upstream test suite passed. Cross-currency cases and additional nonzero charges remain unsupported.

Visual evidence: [installed menu](images/menu-arranque-normal.png) and [menu after removal](images/menu-apos-desinstalar.png). The prepared sources were built locally and the 12 synthetic checks and 14 installer assertions passed. GitHub-hosted CI has not run yet.
