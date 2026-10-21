param(
    [string]$GameDir = 'D:\game\MC\.minecraft\versions\26.3-NeoForge_26.3.0.77',
    [string]$Jdk = '',
    [switch]$Pack
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Get-NumericVersion([string]$name) {
    if ($name -match '(\d+(?:\.\d+)+)') { return [version]$Matches[1] }
    return [version]'0.0'
}

$javac = if ($Jdk) { Join-Path $Jdk 'bin\javac.exe' } else { (Get-Command javac.exe -ErrorAction SilentlyContinue).Source }
$jarTool = if ($Jdk) { Join-Path $Jdk 'bin\jar.exe' } else { (Get-Command jar.exe -ErrorAction SilentlyContinue).Source }
if (-not $javac -or -not (Test-Path $javac)) {
    throw "javac not found. Install JDK 25 or pass -Jdk <path>, e.g. -Jdk C:\jdk-25"
}

$mcRoot = Split-Path -Parent (Split-Path -Parent $GameDir)
$lib = Join-Path $mcRoot 'libraries'
if (-not (Test-Path $lib)) { throw "libraries folder not found: $lib" }

$clientJar = Get-ChildItem $GameDir -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
$neoforge = Get-ChildItem (Join-Path $lib 'net\neoforged\neoforge') -Recurse -Filter '*-universal.jar' |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$loader = Get-ChildItem (Join-Path $lib 'net\neoforged\fancymodloader\loader') -Recurse -Filter 'loader-*.jar' |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$bus = Get-ChildItem (Join-Path $lib 'net\neoforged\bus') -Recurse -Filter 'bus-*.jar' |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$mergetool = Get-ChildItem (Join-Path $lib 'net\neoforged\mergetool') -Recurse -Filter '*-api.jar' |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$gson = Get-ChildItem (Join-Path $lib 'com\google\code\gson\gson') -Recurse -Filter 'gson-*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$slf4j = Get-ChildItem (Join-Path $lib 'org\slf4j\slf4j-api') -Recurse -Filter 'slf4j-api-*.jar' |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$brigadier = Get-ChildItem (Join-Path $lib 'com\mojang\brigadier') -Recurse -Filter 'brigadier-*.jar' |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
$datafixer = Get-ChildItem (Join-Path $lib 'com\mojang\datafixerupper') -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
    Sort-Object { Get-NumericVersion $_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName

$cpItems = @($clientJar, $neoforge, $loader, $bus, $mergetool, $gson, $slf4j, $brigadier, $datafixer) |
    Where-Object { $_ }
Write-Host "classpath:" -ForegroundColor Cyan
$cpItems | ForEach-Object { Write-Host "  $_" }

$build = Join-Path $root 'build'
$classes = Join-Path $build 'classes'
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
Write-Host "compiling $($sources.Count) sources" -ForegroundColor Cyan
& $javac -encoding UTF-8 --release 25 -Xlint:-options '-J-Duser.language=en' '-J-Duser.country=US' -cp ($cpItems -join ';') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with $LASTEXITCODE" }

Copy-Item (Join-Path $root 'src\main\resources\*') $classes -Recurse -Force

$outJar = Join-Path $build 'AI-autoTranslation-1.0.0+mc26.3-neoforge.jar'
if (Test-Path $outJar) { Remove-Item $outJar -Force }
& $jarTool --create --file $outJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed with $LASTEXITCODE" }
Write-Host "built $outJar ($([math]::Round((Get-Item $outJar).Length / 1KB, 1)) KB)" -ForegroundColor Green

if ($Pack) {
    $target = Join-Path $GameDir 'mods\AI-autoTranslation-1.0.0+mc26.3-neoforge.jar'
    Copy-Item $outJar $target -Force
    Write-Host "installed to $target" -ForegroundColor Green
}
