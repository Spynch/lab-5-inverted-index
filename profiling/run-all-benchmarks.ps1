param(
    [string]$OutputFile = "profiling\jmh-all-2026-06-13.json",
    [int]$WarmupIterations = 2,
    [int]$MeasurementIterations = 3,
    [string]$WarmupTime = "500ms",
    [string]$MeasurementTime = "500ms",
    [int]$Forks = 2,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$JarPath = Join-Path $ProjectRoot "target\benchmarks.jar"
$OutputPath = if ([System.IO.Path]::IsPathRooted($OutputFile)) {
    [System.IO.Path]::GetFullPath($OutputFile)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $OutputFile))
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
    throw "Benchmark jar does not exist: $JarPath"
}

$OutputDirectory = [System.IO.Path]::GetDirectoryName($OutputPath)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

Push-Location $ProjectRoot
try {
    & java -jar $JarPath `
        -wi $WarmupIterations `
        -i $MeasurementIterations `
        -w $WarmupTime `
        -r $MeasurementTime `
        -f $Forks `
        -t 1 `
        -rf json `
        -rff $OutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "JMH suite failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "JMH results: $OutputPath"
