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
import searchengine.index.PostingList;
import searchengine.storage.DiskIndexReader;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.MMapIndexReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class MMapReadBenchmark {
    @Benchmark
    public PostingList inMemoryRead(Data data) {
        return data.index.getPostingList("common");
    }

    @Benchmark
    public PostingList diskRead(Data data) throws IOException {
        return data.diskReader.readPostingList("common");
    }

    @Benchmark
    public PostingList diskDocOnlyRead(Data data) throws IOException {
        return data.diskReader.readDocIdPostingList("common");
    }

    @Benchmark
    public PostingList mmapRead(Data data) throws IOException {
        return data.mmapReader.readPostingList("common");
    }

    @Benchmark
    public PostingList mmapDocOnlyRead(Data data) throws IOException {
        return data.mmapReader.readDocIdPostingList("common");
    }

    @State(Scope.Thread)
    public static class Data {
        private InMemoryInvertedIndex index;
        private Path directory;
        private DiskIndexReader diskReader;
        private MMapIndexReader mmapReader;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            index = BenchmarkCorpusFactory.buildIndex(20_000, 80);
            directory = Files.createTempDirectory("mmap-read-benchmark");
            new DiskIndexWriter().write(index, directory);
            diskReader = new DiskIndexReader(directory);
            mmapReader = new MMapIndexReader(directory);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            if (diskReader != null) {
                diskReader.close();
            }
            if (mmapReader != null) {
                mmapReader.close();
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
