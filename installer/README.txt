TRADING 212 ADD-ON INSTALLER 0.1.0
For OFFICIAL Portfolio Performance 0.89.0, Windows 64-bit.
Local CMD/PowerShell installer; this is not a signed EXE installer.

INSTALL
1. Extract the ENTIRE ZIP into a folder you own. Do not run it inside the ZIP.
2. Close Portfolio Performance.
3. Open Install.cmd and select PortfolioPerformance.exe from the official
   0.89.0 installation you normally use.
4. After the success message, open PP using your USUAL shortcut.
5. Open a wallet. Use File > Import > Trading 212 (CSV - add-on).
   The menu is disabled while no wallet is open.
6. For initial testing, open a COPY of your wallet and confirm the cash
   and securities accounts in the wizard before completing the import.

You do not need Abrir-Addon.cmd or the separate launcher from the earlier proof.
This package installs the SAME 0.1.0 importer; it does not add support for FX
or additional charges that are still rejected. It retains the official wizard's
duplicate checks, which produce warnings that can be accepted manually.

WHAT IS CHANGED
Only two original configuration files are adjusted:
configuration/config.ini and
configuration/org.eclipse.equinox.simpleconfigurator/bundles.info.
The JAR is added under addons/trading212, with installation records and backups
in that folder. The executable, startup INI, official libraries and wallets
are not changed.
The derived configuration/org.eclipse.osgi and org.eclipse.core.runtime caches
are moved into the backup; PP regenerates them on the next startup. This prevents
the add-on from remaining cached after removal.
The first startup may take longer. The workspace is not deleted.
During removal, only the add-on's three entries in the persisted UI state
are removed, with a backup of workbench.xmi; the rest of the file is preserved.
The usual workspace is identified from the PP configuration/INI.
If your shortcut uses a custom workspace through the -data argument,
specify it when uninstalling from the command line:
Manage-Addon.ps1 -Mode Uninstall -PortfolioPath "PP folder" -WorkspacePath "workspace"

UNINSTALL
Close PP. Open Uninstall.cmd and select the same executable.
The installer restores both configuration files byte for byte when there
have been no subsequent changes. If they have changed, it removes only its
own entries, preserving subsequent changes. It removes the JAR if its hash
matches the installed version; a modified JAR is preserved and disconnected.
Backups remain in addons/trading212/backups. Wallets are never deleted.

PORTFOLIO PERFORMANCE UPDATES
Recommendation: uninstall the add-on BEFORE updating PP.
This add-on does not participate in PP's p2 update mechanism.
An update may regenerate bundles.info and remove the registration, causing
the menu to disappear. The installer does not guarantee the add-on will survive
updates. A new version requires new validation: this package refuses installation
on 0.89.1 or any other version. Do not force compatibility in the manifest.
If you have already updated, close PP and use Check.cmd. Uninstall.cmd
is designed to clean up the old registration without overwriting the new
version's configuration with an old backup. Then wait for a package validated
for that version. Do not fix this by copying old configuration files over new ones.

PERMISSIONS AND ERRORS
You need write permission in the selected folder. The installer does not
automatically elevate privileges or change Windows security policies.
If the folder is under Program Files, the operation may require administrator
permissions. If Windows blocks the script, report the message;
do not globally disable security policies to run it.
Keep this package for Check.cmd and Uninstall.cmd.

Validation and testing limitations: RESULTADOS.txt.
No personal installation was modified to prepare this package.
