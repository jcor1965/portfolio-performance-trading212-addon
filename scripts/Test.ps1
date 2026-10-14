param([Parameter(Mandatory=$true)][string]$PortfolioPath,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$overlay=Join-Path $root ('.build/selftest-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $overlay|Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-Isolated.ps1') -Destination (Join-Path $overlay 'Start-Addon.ps1')
Copy-Item -LiteralPath (Join-Path $root 'dist/installer/org.trading212.ppaddon_0.1.0.jar') -Destination $overlay
$privateDirectory=$env:PP_TRADING212_PRIVATE_DIR
try {
    $env:PP_TRADING212_PRIVATE_DIR=$null
    & (Join-Path $overlay 'Start-Addon.ps1') -PortfolioPath $PortfolioPath -JavaPath (Join-Path $JavaHome 'bin/java.exe') -SelfTest
    if($LASTEXITCODE -ne 0){throw 'Synthetic OSGi self-test failed.'}
} finally { $env:PP_TRADING212_PRIVATE_DIR=$privateDirectory }
