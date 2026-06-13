param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$InputFile,
    [string]$OutputFile,
    [ValidateSet("Cpu", "Allocation")]
    [string]$Profile = "Cpu",
    [string]$Title,
    [string]$IncludePattern,
    [string]$ExcludePattern,
    [ValidateRange(0, 100)]
    [int]$SkipFrames = 0,
    [ValidateRange(0.0, 100.0)]
    [double]$MinWidth = 0.0,
    [string]$ConverterJar = $env:JFR_CONVERTER_JAR
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$converterVersion = "4.4"
$converterSha256 = "2A4458E66C4F87E4D4B2991F5DEC784BD8AF0CF84E18864F36AECF9BADE35C84"
$converterUrl = "https://github.com/async-profiler/async-profiler/releases/download/v$converterVersion/jfr-converter.jar"
$managedConverter = [string]::IsNullOrWhiteSpace($ConverterJar)

if ($managedConverter) {
    $cacheDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "jfr-converter"
    $ConverterJar = Join-Path $cacheDirectory "jfr-converter-$converterVersion.jar"

    if (-not (Test-Path -LiteralPath $ConverterJar -PathType Leaf)) {
        New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
        Write-Host "Downloading jfr-converter $converterVersion..."
        Invoke-WebRequest -UseBasicParsing -Uri $converterUrl -OutFile $ConverterJar
    }

    $actualSha256 = (Get-FileHash -LiteralPath $ConverterJar -Algorithm SHA256).Hash
    if ($actualSha256 -ne $converterSha256) {
        throw "Unexpected SHA-256 for '$ConverterJar': $actualSha256"
    }
}
$ConverterJar = (Resolve-Path -LiteralPath $ConverterJar).Path

$inputPath = (Resolve-Path -LiteralPath $InputFile).Path
if ([System.IO.Path]::GetExtension($inputPath) -ne ".jfr") {
    throw "Input file must have the .jfr extension: $inputPath"
}

if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $suffix = $Profile.ToLowerInvariant()
    $OutputFile = Join-Path `
        ([System.IO.Path]::GetDirectoryName($inputPath)) `
        "$([System.IO.Path]::GetFileNameWithoutExtension($inputPath))-$suffix-flamegraph.html"
}

if (-not [System.IO.Path]::IsPathRooted($OutputFile)) {
    $OutputFile = Join-Path (Get-Location) $OutputFile
}
$outputPath = [System.IO.Path]::GetFullPath($OutputFile)
$outputDirectory = [System.IO.Path]::GetDirectoryName($outputPath)
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

if ([string]::IsNullOrWhiteSpace($Title)) {
    $Title = "$([System.IO.Path]::GetFileNameWithoutExtension($inputPath)) $Profile flame graph"
}

$profileArguments = switch ($Profile) {
    "Cpu" { @("--state", "runnable") }
    "Allocation" { @("--alloc") }
}

$filterArguments = @()
if (-not [string]::IsNullOrWhiteSpace($IncludePattern)) {
    $filterArguments += @("--include", $IncludePattern)
}
if (-not [string]::IsNullOrWhiteSpace($ExcludePattern)) {
    $filterArguments += @("--exclude", $ExcludePattern)
}
if ($SkipFrames -gt 0) {
    $filterArguments += @("--skip", $SkipFrames)
}
if ($MinWidth -gt 0) {
    $filterArguments += @("--minwidth", $MinWidth.ToString([System.Globalization.CultureInfo]::InvariantCulture))
}

$converterArguments = @(
    "-jar", $ConverterJar
) + $profileArguments + $filterArguments + @(
    "--simple",
    "--norm",
    "--lines",
    "--title", $Title,
    $inputPath,
    $outputPath
)

& java @converterArguments
if ($LASTEXITCODE -ne 0) {
    throw "jfr-converter failed with exit code $LASTEXITCODE"
}
if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
    throw "jfr-converter did not create '$outputPath'"
}

Write-Host "Flame graph: $outputPath"
