# Compatibility and updates

The add-on is an Eclipse fragment attached to `name.abuchen.portfolio.ui`. It uses internal UI classes, not a stable public importer SPI. Host dependencies are restricted to `[0.89.0,0.89.1)` and the installer additionally requires exactly `0.89.0`.

The normal launcher uses the registration added to `bundles.info`. This registration is not managed by the application's p2 update profile. An update can regenerate that file, remove the registration, or make the fragment incompatible. A copied/new installation also requires installation of a validated add-on version.

Uninstall before a host update. If already updated, use `Verificar.cmd` and `Desinstalar.cmd`. Uninstall restores original configuration only when its current hashes still match the installed hashes; otherwise it removes its own registration without restoring old host metadata. Backups are retained and validated before restoration.

Update checks were local simulations of host metadata changes and regenerated bundle configuration. A real update to a later released version was not performed. No compatibility with later releases is claimed.

The default workspace is resolved from the application INI or `osgi.instance.area.default`. For a custom shortcut with `-data`, supply `-WorkspacePath` to `Manage-Addon.ps1` during removal, as documented in the installer instructions.
