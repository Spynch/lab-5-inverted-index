package searchengine.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.compression.IndexCompressionMode;
import searchengine.ranking.SearchResult;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.DiskSearchEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class DiskSearchBenchmark {
    @Benchmark
    public List<SearchResult> inMemorySearch(Data data) {
        return data.index.search(data.query, 10);
    }

    @Benchmark
    public List<SearchResult> diskSearch(Data data) throws IOException {
        return data.diskSearch.search(data.query, 10);
    }

    @Benchmark
    public List<SearchResult> mmapSearch(Data data) throws IOException {
        return data.mmapSearch.search(data.query, 10);
    }

    @State(Scope.Thread)
    public static class Data {
        @org.openjdk.jmh.annotations.Param({"none", "delta", "varbyte", "delta-varbyte",
                "bitpacking", "delta-bitpacking", "pfor", "delta-pfor"})
        public String compression;

        private InMemoryInvertedIndex index;
        private Path directory;
        private DiskSearchEngine diskSearch;
        private DiskSearchEngine mmapSearch;
        private String query;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            index = BenchmarkCorpusFactory.buildIndex(20_000, 80);
            directory = Files.createTempDirectory("disk-search-benchmark");
            new DiskIndexWriter(IndexCompressionMode.fromId(compression)).write(index, directory);
            diskSearch = DiskSearchEngine.disk(directory);
            mmapSearch = DiskSearchEngine.mmap(directory);
            query = "(common AND medium1) OR (term42 NEAR/4 common)";
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            if (diskSearch != null) {
                diskSearch.close();
            }
            if (mmapSearch != null) {
                mmapSearch.close();
            }
            deleteRecursively(directory);
        }

        private static void deleteRecursively(Path root) throws IOException {
            if (root == null || !Files.exists(root)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
    }
}
