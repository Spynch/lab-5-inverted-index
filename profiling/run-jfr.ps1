param(
    [string]$Benchmark = "QueryPipelineBenchmark.execute",
    [string]$OutputDirectory = "profiling\jfr",
    [string]$FlameGraphOutput,
    [string]$IncludePattern,
    [int]$SkipFrames = 0,
    [double]$MinWidth = 0.0,
    [switch]$SkipBuild,
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$JmhArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $SkipBuild) {
    & mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }
}

& java -jar target\benchmarks.jar $Benchmark `
    -prof "jfr:dir=$OutputDirectory;configName=profile;stackDepth=128" `
    @JmhArguments
if ($LASTEXITCODE -ne 0) {
    throw "JMH benchmark failed with exit code $LASTEXITCODE"
}

$recording = Get-ChildItem -LiteralPath $OutputDirectory -Filter "profile.jfr" -File -Recurse |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $recording) {
    throw "No profile.jfr was created under '$OutputDirectory'"
}

$converterArguments = @{
    InputFile = $recording.FullName
    Profile = "Cpu"
    Title = "$Benchmark CPU flame graph"
}
if (-not [string]::IsNullOrWhiteSpace($FlameGraphOutput)) {
    $converterArguments.OutputFile = $FlameGraphOutput
}
if (-not [string]::IsNullOrWhiteSpace($IncludePattern)) {
    $converterArguments.IncludePattern = $IncludePattern
}
if ($SkipFrames -gt 0) {
    $converterArguments.SkipFrames = $SkipFrames
}
if ($MinWidth -gt 0) {
    $converterArguments.MinWidth = $MinWidth
}

& (Join-Path $PSScriptRoot "jfr-to-flamegraph.ps1") @converterArguments
