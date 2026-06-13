# Profiling

Build benchmark jar first:

```bash
mvn -DskipTests package
```

Current results are summarized in `profiling/results-2026-06-13.md`.

## Full JMH suite

Runs every benchmark and every parameter combination with one comparable
configuration and writes 114 result objects:

```powershell
.\profiling\run-all-benchmarks.ps1 -SkipBuild
```

Current artifacts:

- `jmh-all-2026-06-13.json`
- `jmh-query-pipeline-gc-2026-06-13.json`

## JMH GC profiler

Shows allocation rate and GC pressure while running selected benchmark:

```bash
java -jar target/benchmarks.jar MMapReadBenchmark -prof gc
java -jar target/benchmarks.jar CompressionBenchmark -prof gc
java -jar target/benchmarks.jar DiskSearchBenchmark -prof gc
```

## Java Flight Recorder

Use the JMH JFR profiler so the launcher and fork do not write the same file.
The script records JFR and converts the latest recording to an interactive
CPU flame graph:

```powershell
.\profiling\run-jfr.ps1 `
  -Benchmark QueryPipelineBenchmark.execute `
  -OutputDirectory profiling\jfr-search `
  -FlameGraphOutput profiling\flamegraphs\query-pipeline-cpu.html `
  -IncludePattern ".*QueryPipelineBenchmark.*" `
  -SkipFrames 16 `
  -MinWidth 0.25 `
  -JmhArguments @("-wi", "1", "-w", "5s", "-i", "4", "-r", "5s", "-f", "1")
```

The first conversion downloads the pinned standalone `jfr-converter` 4.4 to
the system temporary directory and verifies its SHA-256. Set
`JFR_CONVERTER_JAR` or pass `-ConverterJar` to use a local converter instead.

Convert an existing recording without rerunning the benchmark:

```powershell
.\profiling\jfr-to-flamegraph.ps1 `
  -InputFile profiling\jfr-search\...\profile.jfr `
  -OutputFile profiling\flamegraphs\query-pipeline-cpu.html `
  -Profile Cpu `
  -IncludePattern ".*QueryPipelineBenchmark.*" `
  -SkipFrames 16 `
  -MinWidth 0.25 `
  -Title "Query pipeline CPU flame graph"
```

Use `-Profile Allocation` to build an allocation flame graph. CPU conversion
uses `STATE_RUNNABLE`, which is how standard JDK JFR marks execution samples.
`-IncludePattern`, `-SkipFrames` and `-MinWidth` can focus the output on
application stacks and remove benchmark-launcher frames from the bottom.

Open `.jfr` recordings in Java Mission Control for the complete event view.

Generated examples:

- [Query pipeline CPU flame graph](flamegraphs/query-pipeline-cpu.html)
- [Query pipeline allocation flame graph](flamegraphs/query-pipeline-allocation.html)
- [Index build CPU flame graph](flamegraphs/index-build-cpu.html)
- [Index build allocation flame graph](flamegraphs/index-build-allocation.html)

## VisualVM

Run a long benchmark and attach VisualVM to the Java process:

```bash
java -jar target/benchmarks.jar MMapReadBenchmark -wi 5 -i 10 -f 1
```

Inspect CPU sampler, allocation sampler and heap usage.

## async-profiler

Set `ASYNC_PROFILER_HOME` to the async-profiler directory, start a benchmark, then attach by PID:

```powershell
$env:ASYNC_PROFILER_HOME="C:\tools\async-profiler"
java -jar target\benchmarks.jar MMapReadBenchmark -wi 5 -i 10 -f 1
```

In another terminal:

```powershell
& "$env:ASYNC_PROFILER_HOME\bin\asprof.exe" -d 30 -e cpu -f profiling\cpu-flamegraph.html <pid>
& "$env:ASYNC_PROFILER_HOME\bin\asprof.exe" -d 30 -e alloc -f profiling\alloc-flamegraph.html <pid>
```

What to check:

- `PostingListOperations.andWithSkips` vs `andWithoutSkips` CPU time
- `DiskIndexCodec.decodePostingList` decompression and object allocation cost
- `DiskIndexCodec.decodeDocIdPostingList` docId/tf-only read cost for boolean/BM25 workloads
- `DiskPostingCursor` and `StreamingQueryExecutor` streaming query execution without materializing term posting lists
- `DiskIndexReader.readSlice` regular disk read overhead
- `PagedMMapFile.readSlice` mmap page-cache hit/miss behavior
- `DiskSearchEngine.search` disk-backed query execution and BM25 scoring
- dictionary memory footprint through retained heap in VisualVM/JFR

Current snapshots are under `profiling/jfr-search-2026-06-13-current` and
`profiling/jfr-index-build-2026-06-13-current`. Older recordings are retained
for comparison.
