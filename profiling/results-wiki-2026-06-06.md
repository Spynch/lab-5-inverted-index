# Wiki Benchmark Run 2026-06-06

Environment:

- JDK: 21.0.10
- Dataset: `data/ruwiki-latest-pages-articles.xml`
- Dataset size: 34,334,999,649 bytes
- Machine snapshot before wiki indexing: 32,947,148 KB total memory, 14,069,292 KB free physical memory
- Free disk on `E:` before wiki indexing: 244,570,382,336 bytes

## Build

Command:

```powershell
mvn -DskipTests package
```

Result: success.

## JMH Smoke

The existing JMH benchmarks use synthetic corpora from `BenchmarkCorpusFactory`; they do not read the Wikipedia dump.

Command:

```powershell
java -jar target\benchmarks.jar -wi 0 -i 1 -r 1s -f 1 -foe true -rf json -rff profiling\jmh-smoke-wiki-data-2026-06-06.json
```

Result JSON: `profiling/jmh-smoke-wiki-data-2026-06-06.json`

Summary:

| Benchmark | Score |
| --- | ---: |
| BooleanQueryBenchmark.andWithSkips | 918.620 us/op |
| BooleanQueryBenchmark.andWithoutSkips | 603.570 us/op |
| CompressionBenchmark.compress none | 72.772 us/op |
| CompressionBenchmark.compress varbyte | 437.488 us/op |
| CompressionBenchmark.compress delta-varbyte | 347.167 us/op |
| CompressionBenchmark.compress bitpacking | 5612.502 us/op |
| CompressionBenchmark.decompress none | 102.108 us/op |
| CompressionBenchmark.decompress varbyte | 365.045 us/op |
| CompressionBenchmark.decompress delta-varbyte | 233.326 us/op |
| CompressionBenchmark.decompress bitpacking | 7245.864 us/op |
| DiskIndexWriteBenchmark.writeIndex | 75.033 ms/op |
| DiskSearchBenchmark.diskSearch | 4938.342 us/op |
| DiskSearchBenchmark.inMemorySearch | 1179.628 us/op |
| DiskSearchBenchmark.mmapSearch | 5527.401 us/op |
| MMapReadBenchmark.diskDocOnlyRead | 381.106 us/op |
| MMapReadBenchmark.diskRead | 1497.725 us/op |
| MMapReadBenchmark.inMemoryRead | 0.037 us/op |
| MMapReadBenchmark.mmapDocOnlyRead | 195.329 us/op |
| MMapReadBenchmark.mmapRead | 1830.795 us/op |

## Full JMH Attempt

Command:

```powershell
java -jar target\benchmarks.jar -rf json -rff profiling\jmh-results-2026-06-06.json
```

Result: stopped after the 15 minute shell timeout. The JSON result file was empty and should not be used.

## Wikipedia Indexing Attempt: 100000 Documents

Command:

```powershell
':quit' | java -Xmx12g -cp target\benchmarks.jar searchengine.SearchCli --wiki data\ruwiki-latest-pages-articles.xml --max-docs 100000 --index-dir target\wiki-index --reader mmap
```

Result: failed with `OutOfMemoryError: Java heap space` after about 442 seconds.

Observed failing stack:

```text
java.lang.OutOfMemoryError: Java heap space
  at java.base/java.util.stream.IntPipeline.toArray(IntPipeline.java:562)
  at searchengine.index.InMemoryInvertedIndex.build(InMemoryInvertedIndex.java:72)
  at searchengine.SearchCli.buildIndex(SearchCli.java:55)
```

Conclusion: the current CLI builds a full in-memory index before writing to disk, so 100000 Wikipedia documents did not fit into a 12 GB heap on this machine.

## Wikipedia Indexing: 10000 Documents

Command:

```powershell
':quit' | java -Xmx12g -cp target\benchmarks.jar searchengine.SearchCli --wiki data\ruwiki-latest-pages-articles.xml --max-docs 10000 --index-dir target\wiki-index --reader mmap
```

Result:

```text
Building index...
Indexed 10000 documents, 1110333 terms in 47765 ms.
ExitCode=0
ElapsedSeconds=48.643
```

Index metadata from `target/wiki-index/meta.json`:

```json
{
  "formatVersion": 2,
  "documentCount": 10000,
  "termCount": 1110333,
  "avgDocumentLength": 2816.52550000,
  "docIdCompression": "delta-varbyte",
  "tfCompression": "varbyte",
  "positionCompression": "gap-varbyte",
  "dictionaryCompression": "deflate",
  "documentCompression": "deflate",
  "dictionaryBytes": 15164292,
  "postingsBytes": 34734258,
  "positionsBytes": 46366656,
  "documentsBytes": 66091,
  "totalBytes": 96331736
}
```

## Wikipedia Search Smoke

Reader: `mmap`

The CLI reads stdin as UTF-8. On Windows PowerShell, Cyrillic queries must be piped as UTF-8 bytes; otherwise the query text is corrupted before Java reads it.

Queries and observed timings:

| Query | Result | Time |
| --- | --- | ---: |
| `ref` | 5 results | 76 ms |
| `https` | 5 results | 7 ms |
| `ref AND https` | 5 results | 14 ms |
| `https NEAR/5 ref` | 5 results | 39 ms |
| `org AND ru` | 5 results | 7 ms |
| `категория` | 5 results | 77 ms |
| `год` | 5 results | 7 ms |
| `категория AND год` | 5 results | 14 ms |
| `год NEAR/5 категория` | 5 results | 21 ms |
| `примечания AND ref` | 5 results | 6 ms |
