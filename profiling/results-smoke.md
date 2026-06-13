# JMH Smoke Results

Date: 2026-06-06

Command:

```powershell
java -jar target\benchmarks.jar -wi 0 -i 1 -r 1s -f 1 -rf json -rff profiling\jmh-smoke-results.json
```

These numbers are smoke-test measurements only. They verify that all benchmark entry points run and produce output. Use normal warmup and more measurement iterations for defensible final results.

| Benchmark | Score | Unit |
|---|---:|---|
| BooleanQueryBenchmark.andWithSkips | 928.173 | us/op |
| BooleanQueryBenchmark.andWithoutSkips | 566.870 | us/op |
| CompressionBenchmark.compress none | 72.776 | us/op |
| CompressionBenchmark.compress varbyte | 434.169 | us/op |
| CompressionBenchmark.compress delta-varbyte | 284.632 | us/op |
| CompressionBenchmark.compress bitpacking | 5844.720 | us/op |
| CompressionBenchmark.decompress none | 104.703 | us/op |
| CompressionBenchmark.decompress varbyte | 362.518 | us/op |
| CompressionBenchmark.decompress delta-varbyte | 226.676 | us/op |
| CompressionBenchmark.decompress bitpacking | 7130.639 | us/op |
| DiskIndexWriteBenchmark.writeIndex | 71.359 | ms/op |
| DiskSearchBenchmark.diskSearch | 4988.068 | us/op |
| DiskSearchBenchmark.inMemorySearch | 1413.056 | us/op |
| DiskSearchBenchmark.mmapSearch | 4922.623 | us/op |
| MMapReadBenchmark.diskDocOnlyRead | 280.907 | us/op |
| MMapReadBenchmark.diskRead | 1619.244 | us/op |
| MMapReadBenchmark.inMemoryRead | 0.051 | us/op |
| MMapReadBenchmark.mmapDocOnlyRead | 211.985 | us/op |
| MMapReadBenchmark.mmapRead | 1910.896 | us/op |

Immediate observations:

- DocId/tf-only reads are materially faster than full posting-list reads because they skip positions decoding.
- `DiskSearchBenchmark` uses streaming query execution and a top-K heap instead of materializing candidate posting lists.
- This synthetic skip benchmark still does not favor skips; use a more skewed benchmark dataset before claiming a speedup from skip pointers.
