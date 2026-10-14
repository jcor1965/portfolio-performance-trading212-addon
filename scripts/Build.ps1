param([Parameter(Mandatory=$true)][string]$PortfolioPath,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$official=(Resolve-Path -LiteralPath $PortfolioPath).Path
if(-not(Test-Path -LiteralPath (Join-Path $official 'plugins/name.abuchen.portfolio.ui_0.89.0.jar'))) { throw 'Official host 0.89.0 required.' }
if(-not $JavaHome) { throw 'Supply -JavaHome for JDK 21.' }
$jdk=(Resolve-Path -LiteralPath $JavaHome).Path
$javac=Join-Path $jdk 'bin/javac.exe';$jar=Join-Path $jdk 'bin/jar.exe'
$version=& $javac -version 2>&1
if($LASTEXITCODE -ne 0 -or "$version" -notmatch '^javac 21\.') { throw 'JDK 21 required.' }
$build=Join-Path $root ('.build/compile-'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $build 'classes';$dist=Join-Path $root 'dist';$installer=Join-Path $dist 'installer'
New-Item -ItemType Directory -Force -Path $classes,$installer|Out-Null
# Compile against the official host libraries; do not redistribute or patch them.
$classpath=((Get-ChildItem -LiteralPath (Join-Path $official 'plugins') -Filter '*.jar'|Where-Object {$_.Name -notmatch '\.nl_'}).FullName)-join ';'
$arguments=@('--release','21','-encoding','UTF-8','-classpath',$classpath,'-d',$classes)
$arguments+=@(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -Filter '*.java'|ForEach-Object {$_.FullName})
$argFile=Join-Path $build 'javac.args'
$utf8=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllLines($argFile,@($arguments|ForEach-Object {if($_.Contains('"')){throw 'Quote in argument not supported.'}; '"'+$_.Replace('\','/')+'"'}),$utf8)
& $javac ('@'+$argFile)
if($LASTEXITCODE -ne 0){throw 'Compilation failed.'}
$artifact=Join-Path $installer 'org.trading212.ppaddon_0.1.0.jar'
& $jar --create --file $artifact --manifest (Join-Path $root 'META-INF/MANIFEST.MF') -C $classes . -C $root fragment.xml -C $root LICENSE.txt -C (Join-Path $root 'resources') activity2026.csv
if($LASTEXITCODE -ne 0){throw 'JAR creation failed.'}
Copy-Item -Path (Join-Path $root 'installer/*') -Destination $installer -Force
Copy-Item -Path (Join-Path $root 'docs/images/*.png') -Destination $installer -Force
$hash=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
[IO.File]::WriteAllText((Join-Path $installer 'addon.sha256'),$hash,[Text.Encoding]::ASCII)
# Generate the three English entry points from a single shared installer.
foreach($pair in @(@('Install','Install'),@('Uninstall','Uninstall'),@('Check','Status'))) {
    $cmd='@echo off' + "`r`n" + 'powershell.exe -NoProfile -STA -File "%~dp0Manage-Addon.ps1" -Mode '+$pair[1]+"`r`n"+'if errorlevel 1 pause'+"`r`n"
    [IO.File]::WriteAllText((Join-Path $installer ($pair[0]+'.cmd')),$cmd,[Text.Encoding]::ASCII)
}
Copy-Item -LiteralPath (Join-Path $root 'LICENSE.txt') -Destination $installer -Force
$zip=Join-Path $dist 'Trading212-Addon-Installer-0.1.0-PP0.89.0-Windows64.zip'
Compress-Archive -Path $installer -DestinationPath $zip -Force
$zipHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($zip+'.sha256'),($zipHash+'  '+[IO.Path]::GetFileName($zip)+"`n"),[Text.Encoding]::ASCII)
Write-Output ('Built '+$zip)
