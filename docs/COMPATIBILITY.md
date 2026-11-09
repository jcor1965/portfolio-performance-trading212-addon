# Compatibility and updates

The add-on is an Eclipse fragment attached to `name.abuchen.portfolio.ui`. It uses internal UI classes, not a stable public importer SPI. The OSGi interval `[0.89.0,0.90.1)` allows one fragment to run on both supported hosts. The installer and the add-on’s menu and import entry points explicitly allow only `0.89.0` and `0.90.0`.

The normal launcher uses the registration added to `bundles.info`. This registration is not managed by the application's p2 update profile. An update can regenerate that file, remove the registration, or make the fragment incompatible. A copied/new installation also requires installation of a validated add-on version.

For an installed v0.1.0, use `Uninstall.cmd` from the v0.1.1 package before installing v0.1.1. Uninstall before a host update. If already updated, use `Check.cmd` and `Uninstall.cmd`. Uninstall restores original configuration only when its current hashes still match the installed hashes; otherwise it removes its own registration without restoring old host metadata. Backups are retained and validated before restoration.

Host update checks simulate changes to the bundle metadata and regeneration of the bundle configuration. Clean installations use the official Windows x64 distributions for both supported versions.

The default workspace is resolved from the application INI or `osgi.instance.area.default`. For a custom shortcut with `-data`, supply `-WorkspacePath` to `Manage-Addon.ps1` during removal, as documented in the installer instructions.
