param(
    [Parameter(Mandatory=$true)][string]$PortfolioPath,
    [string]$JavaPath,
    [switch]$SelfTest,
    [switch]$Wait
)
$ErrorActionPreference = 'Stop'
$official = (Resolve-Path -LiteralPath $PortfolioPath).Path.TrimEnd('\')
$hostJar = Join-Path $official 'plugins/name.abuchen.portfolio.ui_0.89.0.jar'
if (-not (Test-Path -LiteralPath $hostJar)) { throw 'Este POC exige a distribuicao oficial 0.89.0. Nenhum ficheiro foi alterado.' }
if ($official.Contains(',')) { throw 'Para este POC, escolha uma pasta sem virgulas no caminho.' }
$addon = Join-Path $PSScriptRoot 'org.trading212.ppaddon_0.1.0.jar'
if (-not (Test-Path -LiteralPath $addon)) { throw 'JAR do complemento em falta.' }
$stateRoot = Join-Path $PSScriptRoot 'state'
$configRoot = Join-Path $stateRoot 'configuration'
$simpleRoot = Join-Path $configRoot 'org.eclipse.equinox.simpleconfigurator'
New-Item -ItemType Directory -Force -Path $simpleRoot,(Join-Path $stateRoot 'home') | Out-Null
function FileUri([string]$path) { return ([Uri]([IO.Path]::GetFullPath($path))).AbsoluteUri.Replace(',', '%2C') }
$bundleLines = Get-Content -LiteralPath (Join-Path $official 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info')
$resolvedLines = foreach ($line in $bundleLines) {
    if ($line.StartsWith('#') -or [string]::IsNullOrWhiteSpace($line)) { $line; continue }
    $fields = $line.Split(',')
    if ($fields.Length -ne 5 -or -not $fields[2].StartsWith('plugins/')) { throw 'Formato bundles.info nao suportado; original preservado.' }
    $fields[2] = FileUri (Join-Path $official $fields[2])
    $fields -join ','
}
$resolvedLines += 'org.trading212.ppaddon,0.1.0,' + (FileUri $addon) + ',4,false'
$utf8 = [Text.UTF8Encoding]::new($false)
$bundleInfo = Join-Path $simpleRoot 'bundles.info'
[IO.File]::WriteAllLines($bundleInfo, $resolvedLines, $utf8)
$config = Get-Content -Raw -LiteralPath (Join-Path $official 'configuration/config.ini')
$framework = Get-ChildItem -LiteralPath (Join-Path $official 'plugins') -Filter 'org.eclipse.osgi_*.jar' | Select-Object -First 1
$simple = Get-ChildItem -LiteralPath (Join-Path $official 'plugins') -Filter 'org.eclipse.equinox.simpleconfigurator_*.jar' | Select-Object -First 1
$compat = Get-ChildItem -LiteralPath (Join-Path $official 'plugins') -Filter 'org.eclipse.osgi.compatibility.state_*.jar' | Select-Object -First 1
$overrides = @{
    'org.eclipse.equinox.simpleconfigurator.configUrl' = FileUri $bundleInfo
    'osgi.framework' = FileUri $framework.FullName
    'osgi.bundles' = 'reference:' + (FileUri $simple.FullName) + '@1:start'
    'osgi.framework.extensions' = 'reference:' + (FileUri $compat.FullName)
    'osgi.instance.area.default' = FileUri (Join-Path $stateRoot 'workspace')
}
foreach ($key in $overrides.Keys) {
    $pattern = '(?m)^' + [regex]::Escape($key) + '=.*$'
    $replacement = $key + '=' + $overrides[$key]
    $config = [regex]::Replace($config, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $replacement })
}
[IO.File]::WriteAllText((Join-Path $configRoot 'config.ini'), $config, $utf8)
$arguments = @('-configuration', $configRoot, '-data', (Join-Path $stateRoot 'workspace'), '-user', (Join-Path $stateRoot 'user'), '-clean')
if ($JavaPath) { $arguments += @('-vm', (Resolve-Path -LiteralPath $JavaPath).Path) }
if ($SelfTest) {
    $arguments += @('-application', 'name.abuchen.portfolio.ui.selftest', '-consoleLog')
}
$ini = @(Get-Content -LiteralPath (Join-Path $official 'PortfolioPerformance.ini'))
$vmIndex = [Array]::IndexOf($ini, '-vmargs')
$vmArgs = if ($vmIndex -ge 0) { @($ini | Select-Object -Skip ($vmIndex + 1) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) } else { @() }
if ($SelfTest) {
    if (-not $JavaPath) { throw 'Para SelfTest, indique JavaPath para java.exe (Java 21).' }
    $launcher = Get-ChildItem -LiteralPath (Join-Path $official 'plugins') -Filter 'org.eclipse.equinox.launcher_*.jar' | Select-Object -First 1
    $javaArguments = $vmArgs + @('-Dname.abuchen.portfolio.in-app-update=disable', ('-Duser.home=' + (Join-Path $stateRoot 'home')), ('-Dt212.addon.evidence=' + (Join-Path $stateRoot 'evidence')), '-jar', $launcher.FullName, '-install', $official, '-configuration', $configRoot, '-data', (Join-Path $stateRoot 'workspace'), '-clean', '-nosplash', '-application', 'name.abuchen.portfolio.ui.selftest', '-consoleLog')
    & $JavaPath @javaArguments
    exit $LASTEXITCODE
}
$arguments += @('-vmargs') + $vmArgs + @('-Dname.abuchen.portfolio.in-app-update=disable', ('-Duser.home=' + (Join-Path $stateRoot 'home')), ('-Dt212.addon.evidence=' + (Join-Path $stateRoot 'evidence')))
function QuoteArgument([string]$value) {
    if ($value.Contains('"')) { throw 'Aspas num argumento nao sao suportadas neste POC.' }
    return '"' + $value + '"'
}
$commandArguments = ($arguments | ForEach-Object { QuoteArgument $_ }) -join ' '
$parameters = @{ FilePath=(Join-Path $official 'PortfolioPerformance.exe'); ArgumentList=$commandArguments; WorkingDirectory=$official; PassThru=$true }
if ($Wait -or $SelfTest) { $parameters.Wait = $true }
$process = Start-Process @parameters
Write-Output ('PID=' + $process.Id)
if ($Wait -or $SelfTest) { exit $process.ExitCode }
