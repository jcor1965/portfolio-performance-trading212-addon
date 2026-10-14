param(
    [ValidateSet('Install','Uninstall','Status')][string]$Mode='Install',
    [string]$PortfolioPath,
    [string]$WorkspacePath,
    [switch]$Quiet
)
$ErrorActionPreference='Stop'
$utf8=[Text.UTF8Encoding]::new($false)
$symbol='org.trading212.ppaddon'
$jarName='org.trading212.ppaddon_0.1.0.jar'
$marker="`r`n# BEGIN Trading212 independent add-on`r`nosgi.checkConfiguration=true`r`n# END Trading212 independent add-on`r`n"
# Verify package and backup integrity without depending on optional PowerShell modules.
function Hash([string]$p) {
    $sha=[Security.Cryptography.SHA256]::Create()
    $stream=[IO.File]::OpenRead($p)
    try { ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','') }
    finally { $stream.Dispose(); $sha.Dispose() }
}
# Replace a configuration file atomically; retain recovery backups separately.
function WriteAtomic([string]$path,[byte[]]$bytes) {
    $tmp=$path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    $previous=$tmp+'.previous'
    try { [IO.File]::WriteAllBytes($tmp,$bytes); [IO.File]::Replace($tmp,$path,$previous) }
    finally {
        if(Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp }
        if(Test-Path -LiteralPath $previous) { Remove-Item -LiteralPath $previous }
    }
}
function SaveState($value) { [IO.File]::WriteAllText($statePath,($value|ConvertTo-Json -Depth 6),$utf8) }
function ReadText([string]$p) { $utf8.GetString([IO.File]::ReadAllBytes($p)) }
# Resolve the workspace and remove only the three UI nodes owned by this add-on.
# Back up the model and preserve every other byte, including user layout settings.
function RemovePersistedMenu([string]$backup) {
    $workspace=$WorkspacePath
    if(-not $workspace) {
        $ini=@([IO.File]::ReadAllLines((Join-Path $root 'PortfolioPerformance.ini')))
        $dataIndex=[Array]::IndexOf($ini,'-data')
        if($dataIndex -ge 0 -and $dataIndex+1 -lt $ini.Length) { $workspace=$ini[$dataIndex+1] }
        else {
            $setting=[regex]::Match((ReadText $config),'(?m)^osgi\.instance\.area\.default=(.*)\r?$')
            if($setting.Success) { $workspace=$setting.Groups[1].Value.Trim() }
        }
        if($workspace) {
            $workspace=$workspace.Replace('$LOCALAPPDATA$',$env:LOCALAPPDATA).Replace('$USERPROFILE$',$env:USERPROFILE).Replace('@user.home',$env:USERPROFILE).Replace('\:',':')
            if($workspace.StartsWith('file:')) { $workspace=([Uri]$workspace).LocalPath }
        }
    }
    if(-not $workspace) { Write-Warning 'Workspace not identified; use -WorkspacePath to remove an old menu entry.'; return }
    if(-not [IO.Path]::IsPathRooted($workspace)) { $workspace=Join-Path $root $workspace }
    $model=Join-Path $workspace '.metadata/.plugins/org.eclipse.e4.workbench/workbench.xmi'
    if(-not(Test-Path -LiteralPath $model)) { return }
    $content=ReadText $model
    $pattern='<(?:[\w]+:)?(?:children|handlers|commands)\b[^>]*\belementId="org\.trading212\.ppaddon\.import(?:\.handler)?"[^>]*/>'
    $clean=[regex]::Replace($content,$pattern,'')
    if($clean -match 'elementId="org\.trading212\.ppaddon\.import(?:\.handler)?"') { throw 'Unexpected persisted menu format; no preferences were changed.' }
    if($clean -ne $content) {
        $xml=New-Object Xml.XmlDocument
        $xml.LoadXml($clean)
        $modelBackup=Join-Path $backup ('workbench-'+[Guid]::NewGuid().ToString('N')+'.xmi')
        Copy-Item -LiteralPath $model -Destination $modelBackup
        WriteAtomic $model ($utf8.GetBytes($clean))
    }
}
function ArchiveCaches([string]$backup) {
    # Derived Eclipse configuration only; never workspace/preferences/wallets.
    $cacheBackup=Join-Path $backup ('cache-'+[Guid]::NewGuid().ToString('N'))
    foreach($name in @('org.eclipse.osgi','org.eclipse.core.runtime')) {
        $cache=[IO.Path]::GetFullPath((Join-Path $root ('configuration/'+$name)))
        $expectedPrefix=[IO.Path]::GetFullPath((Join-Path $root 'configuration'))+'\'
        if(-not $cache.StartsWith($expectedPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'Cache path is outside the configuration directory.' }
        if(Test-Path -LiteralPath $cache) {
            New-Item -ItemType Directory -Force -Path $cacheBackup|Out-Null
            Move-Item -LiteralPath $cache -Destination (Join-Path $cacheBackup $name)
        }
    }
}
# Read the registered UI host version instead of guessing from folder names.
function GetVersion {
    $rows=[IO.File]::ReadAllLines($bundles)
    $hostRows=@($rows|Where-Object { $_ -match '^name\.abuchen\.portfolio\.ui,' })
    if($hostRows.Count -ne 1) { throw 'Unable to identify a single Portfolio Performance version.' }
    $hostRows[0].Split(',')[1]
}
try {
    if(-not $PortfolioPath) {
        Add-Type -AssemblyName System.Windows.Forms
        $picker=New-Object System.Windows.Forms.OpenFileDialog
        $picker.Title='Select the official PortfolioPerformance.exe'
        $picker.Filter='Portfolio Performance|PortfolioPerformance.exe'
        if($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
        $PortfolioPath=Split-Path -Parent $picker.FileName
        $picker.Dispose()
    }
    $root=(Resolve-Path -LiteralPath $PortfolioPath).Path.TrimEnd('\')
    $exe=Join-Path $root 'PortfolioPerformance.exe'
    $bundles=Join-Path $root 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
    $config=Join-Path $root 'configuration/config.ini'
    foreach($p in @($exe,$bundles,$config)) { if(-not(Test-Path -LiteralPath $p -PathType Leaf)) { throw 'This folder does not contain a recognized official Portfolio Performance distribution.' } }
    $own=Join-Path $root 'addons/trading212'
    $statePath=Join-Path $own 'installation.json'
    $targetJar=Join-Path $own $jarName
    $state=if(Test-Path -LiteralPath $statePath) { Get-Content -Raw -LiteralPath $statePath|ConvertFrom-Json } else { $null }
    $version=GetVersion
    if($state -and $state.Root -ne $root) { throw 'The installation record belongs to another folder; no changes were made.' }
    $line='org.trading212.ppaddon,0.1.0,addons/trading212/'+$jarName+',4,false'
    if($Mode -eq 'Status') {
        $registered=@([IO.File]::ReadAllLines($bundles)|Where-Object { $_ -eq $line }).Count -eq 1
        $jarOK=(Test-Path -LiteralPath $targetJar) -and $state -and ((Hash $targetJar) -eq $state.JarHash)
        $compatible=$version -eq '0.89.0'
        $message="Portfolio Performance: $version`r`nCompatible: $compatible`r`nRegistered: $registered`r`nJAR verified: $jarOK"
        Write-Output $message
        if(-not $Quiet) { Add-Type -AssemblyName System.Windows.Forms; [Windows.Forms.MessageBox]::Show($message,'Add-on status')|Out-Null }
        exit 0
    }
    $running=@(Get-CimInstance Win32_Process -Filter "Name='PortfolioPerformance.exe' OR Name='javaw.exe' OR Name='java.exe'" |Where-Object { ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) -or ($_.CommandLine -and $_.CommandLine.IndexOf($root,[StringComparison]::OrdinalIgnoreCase) -ge 0) })
    if($running.Count) { throw 'Close Portfolio Performance running from this folder before continuing.' }
    # Refuse unvalidated hosts and conflicting installations before changing files.
    if($Mode -eq 'Install') {
        if($version -ne '0.89.0') { throw "Version $version has not been validated. This installer supports only 0.89.0. No changes were made." }
        $packageJar=Join-Path $PSScriptRoot $jarName
        if(-not(Test-Path -LiteralPath $packageJar)) { throw 'The add-on JAR is missing from the package.' }
        $expected=(Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'addon.sha256')).Trim()
        if((Hash $packageJar) -ne $expected) { throw 'The JAR does not match the verified package checksum.' }
        if($state -and $state.Phase -eq 'installed') {
            if((Hash $targetJar) -eq $expected -and (ReadText $bundles).Contains($line)) { Write-Output 'The add-on is already installed; no changes were made.'; exit 0 }
            throw 'The installation has changed or been updated. Run Uninstall.cmd followed by Install.cmd; subsequent changes will be preserved.'
        }
        if($state -and $state.Phase -notin @('uninstalled')) { throw 'Incomplete installation: run Uninstall.cmd to recover.' }
        if(Test-Path -LiteralPath $targetJar) { throw 'A JAR already exists at this destination; it will not be overwritten.' }
        if((ReadText $bundles) -match '(?m)^org\.trading212\.ppaddon,') { throw 'An add-on registration already exists; it will not be duplicated.' }
        if((ReadText $config).Contains('# BEGIN Trading212 independent add-on')) { throw 'An installation marker already exists; check the configuration before installing.' }
        # Save original bytes and a recovery journal before registering the fragment.
        $backupName=[Guid]::NewGuid().ToString('N')
        $backup=Join-Path $own ('backups/'+$backupName)
        New-Item -ItemType Directory -Path $backup -Force|Out-Null
        Copy-Item -LiteralPath $bundles -Destination (Join-Path $backup 'bundles.info')
        Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'config.ini')
        $beforeBundles=ReadText $bundles; $beforeConfig=ReadText $config
        $afterBundles=$beforeBundles+"`r`n"+$line+"`r`n"
        $afterConfig=$beforeConfig+$marker
        $state=[ordered]@{Root=$root;Version=$version;Backup=$backupName;Phase='prepared';JarHash=$expected;BundlesHash='';ConfigHash='';BundlesBeforeHash=(Hash $bundles);ConfigBeforeHash=(Hash $config)}
        SaveState $state
        try {
            Copy-Item -LiteralPath $packageJar -Destination $targetJar
            WriteAtomic $bundles ($utf8.GetBytes($afterBundles))
            WriteAtomic $config ($utf8.GetBytes($afterConfig))
            ArchiveCaches $backup
            $state.BundlesHash=Hash $bundles; $state.ConfigHash=Hash $config
            $state.Phase='installed'; SaveState $state
        } catch {
            # Roll back configuration and the owned JAR if installation cannot finish.
            WriteAtomic $bundles ([IO.File]::ReadAllBytes((Join-Path $backup 'bundles.info')))
            WriteAtomic $config ([IO.File]::ReadAllBytes((Join-Path $backup 'config.ini')))
            if((Test-Path -LiteralPath $targetJar) -and (Hash $targetJar) -eq $expected) { Remove-Item -LiteralPath $targetJar }
            $state.Phase='uninstalled';SaveState $state
            throw
        }
        $message='Installed. Open Portfolio Performance using your usual shortcut. Backup: '+$backup
    } else {
        if(-not $state -or $state.Phase -eq 'uninstalled') { Write-Output 'No active installation of this package was found.'; exit 0 }
        if($state.Backup -notmatch '^[a-f0-9]{32}$') { throw 'Invalid backup identifier.' }
        $backup=Join-Path $own ('backups/'+$state.Backup)
        foreach($name in @('bundles.info','config.ini')) { if(-not(Test-Path -LiteralPath (Join-Path $backup $name))) { throw 'A backup is missing; no configuration files will be replaced.' } }
        if((Hash (Join-Path $backup 'bundles.info')) -ne $state.BundlesBeforeHash -or (Hash (Join-Path $backup 'config.ini')) -ne $state.ConfigBeforeHash) { throw 'The backup has changed: automatic recovery was stopped; no configuration files were changed.' }
        RemovePersistedMenu $backup
        ArchiveCaches $backup
        # Restore exact backups only when no later changes occurred. Otherwise,
        # remove our registration while preserving settings written by host updates.
        if((Hash $bundles) -eq $state.BundlesHash) {
            WriteAtomic $bundles ([IO.File]::ReadAllBytes((Join-Path $backup 'bundles.info')))
        } else {
            $current=ReadText $bundles
            $clean=[regex]::Replace($current,'(?m)^org\.trading212\.ppaddon,[^\r\n]*(?:\r?\n|$)','')
            WriteAtomic $bundles ($utf8.GetBytes($clean))
        }
        if((Hash $config) -eq $state.ConfigHash) {
            WriteAtomic $config ([IO.File]::ReadAllBytes((Join-Path $backup 'config.ini')))
        } else {
            $current=ReadText $config
            WriteAtomic $config ($utf8.GetBytes($current.Replace($marker,'')))
        }
        if(Test-Path -LiteralPath $targetJar) {
            if((Hash $targetJar) -eq $state.JarHash) { Remove-Item -LiteralPath $targetJar }
            else { Write-Warning 'The JAR has changed: it was preserved but removed from the loading configuration.' }
        }
        $state.Phase='uninstalled'; SaveState $state
        $message='Add-on uninstalled. Subsequent configuration changes were preserved; backups retained in '+$backup
    }
    Write-Output $message
    if(-not $Quiet) { Add-Type -AssemblyName System.Windows.Forms; [Windows.Forms.MessageBox]::Show($message,'Trading 212 add-on')|Out-Null }
} catch {
    Write-Error $_ -ErrorAction Continue
    if(-not $Quiet) { Add-Type -AssemblyName System.Windows.Forms; [Windows.Forms.MessageBox]::Show($_.Exception.Message,'Trading 212 add-on - error')|Out-Null }
    exit 1
}
