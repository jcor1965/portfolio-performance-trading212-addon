# v0.1.0 — Experimental Windows prerelease

Adds a Trading 212 CSV importer to the normal File → Import menu of official Portfolio Performance 0.89.0 without patching the executable or official libraries.

The Windows installer adjusts registration/configuration, retains verified backups and supports status checks and uninstall. Removal preserves later configuration changes and removes the add-on's persisted menu entries.

Use `Instalar.cmd`, `Desinstalar.cmd` and `Verificar.cmd` from the extracted installer ZIP. Installation is CMD/PowerShell based and is not code-signed. Test with a copy of your wallet.

Supported host is exactly 0.89.0, Windows x86_64, Java 21. Uninstall before host updates. FX/additional charges remain unsupported. Duplicate warnings use Portfolio Performance's generic comparison and can be overridden manually.

Attach the installer ZIP and its SHA-256 file to a **prerelease**. Do not attach broker exports, wallet XMLs or local private test logs.
