# v0.1.1 — Windows prerelease

The same add-on installer supports official Portfolio Performance 0.89.0 and 0.90.0 on Windows x86_64 with Java 21. The import wizard window is titled Trading 212 (CSV).

If add-on v0.1.0 is already installed, use this package’s `Uninstall.cmd` before running `Install.cmd`. The uninstaller uses the existing installation journal and retains its backups.

Import support is unchanged. Duplicate warnings come from the installed Portfolio Performance version. The 0.90.0 detector can also flag manual dividends with no share quantity and some cash interest entries where the amounts differ by the withheld tax.

# v0.1.0 — Experimental Windows prerelease

Adds a Trading 212 CSV importer to the normal File → Import menu of official Portfolio Performance 0.89.0 without patching the executable or official libraries.

The Windows installer adjusts registration/configuration, retains verified backups and supports status checks and uninstall. Removal preserves later configuration changes and removes the add-on's persisted menu entries.

Use `Install.cmd`, `Uninstall.cmd` and `Check.cmd` from the extracted installer ZIP. Installation is CMD/PowerShell based and is not code-signed. Test with a copy of your wallet.

Supported host is exactly 0.89.0, Windows x86_64, Java 21. Uninstall before host updates. FX/additional charges remain unsupported. Duplicate warnings use Portfolio Performance's generic comparison and can be overridden manually.

Attach the installer ZIP and its SHA-256 file to a **prerelease**. Do not attach broker exports, wallet XMLs or local private test logs.
