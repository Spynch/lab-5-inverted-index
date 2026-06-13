param(
    [Parameter(Mandatory=$true)] [int]$Pid,
    [string]$Event = "cpu",
    [string]$Output = "profiling\flamegraph.html",
    [int]$DurationSeconds = 30
)

if (-not $env:ASYNC_PROFILER_HOME) {
    throw "Set ASYNC_PROFILER_HOME to your async-profiler directory."
}

$profiler = Join-Path $env:ASYNC_PROFILER_HOME "bin\asprof.exe"
& $profiler -d $DurationSeconds -e $Event -f $Output $Pid