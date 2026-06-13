param(
    [string]$IndexDirectory = "target\wiki-index-100k-readable-2026-06-13",
    [string]$WikipediaDump = "data\ruwiki-latest-pages-articles.xml",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 8080,
    [ValidateSet("mmap", "disk")]
    [string]$Reader = "mmap",
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$IndexDirectory = $IndexDirectory.Trim()
$WikipediaDump = $WikipediaDump.Trim()
$HostName = $HostName.Trim()

if ([string]::IsNullOrWhiteSpace($IndexDirectory)) {
    throw "IndexDirectory must not be empty"
}
if ([string]::IsNullOrWhiteSpace($WikipediaDump)) {
    throw "WikipediaDump must not be empty"
}

$IndexPath = if ([System.IO.Path]::IsPathRooted($IndexDirectory)) {
    [System.IO.Path]::GetFullPath($IndexDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $IndexDirectory))
}
$DumpPath = if ([System.IO.Path]::IsPathRooted($WikipediaDump)) {
    [System.IO.Path]::GetFullPath($WikipediaDump)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $WikipediaDump))
}
$JarPath = Join-Path $ProjectRoot "target\benchmarks.jar"

if (-not (Test-Path -LiteralPath $IndexPath -PathType Container)) {
    throw "Index directory does not exist: $IndexPath"
}
if (-not (Test-Path -LiteralPath $DumpPath -PathType Leaf)) {
    throw "Wikipedia dump does not exist: $DumpPath"
}

if (-not $SkipBuild) {
    Push-Location $ProjectRoot
    try {
        & mvn -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "Application jar does not exist: $JarPath"
}

& java -cp $JarPath searchengine.web.SearchWebServer `
    --index-dir $IndexPath `
    --source-wiki $DumpPath `
    --host $HostName `
    --port $Port `
    --reader $Reader
