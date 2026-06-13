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
import searchengine.storage.DiskIndexStats;
import searchengine.storage.DiskIndexWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(2)
public class DiskIndexWriteBenchmark {
    @Benchmark
    public DiskIndexStats writeIndex(Data data) throws IOException {
        Path directory = data.root.resolve("index-" + data.counter.incrementAndGet());
        return data.writer.write(data.index, directory);
    }

    @State(Scope.Thread)
    public static class Data {
        private InMemoryInvertedIndex index;
        private DiskIndexWriter writer;
        private Path root;
        private AtomicInteger counter;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            index = BenchmarkCorpusFactory.buildIndex(10_000, 80);
            writer = new DiskIndexWriter();
            root = Files.createTempDirectory("disk-index-write-benchmark");
            counter = new AtomicInteger();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            deleteRecursively(root);
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
