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
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(2)
public class IndexLoadBenchmark {
    @Benchmark
    public int loadDisk(Data data) throws IOException {
        try (DiskIndexReader reader = new DiskIndexReader(data.directory)) {
            return reader.getDocumentCount();
        }
    }

    @Benchmark
    public int loadMmap(Data data) throws IOException {
        try (MMapIndexReader reader = new MMapIndexReader(data.directory)) {
            return reader.getDocumentCount();
        }
    }

    @State(Scope.Thread)
    public static class Data {
        private Path directory;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            directory = Files.createTempDirectory("index-load-benchmark");
            new DiskIndexWriter().write(BenchmarkCorpusFactory.buildIndex(20_000, 80), directory);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            if (directory == null || !Files.exists(directory)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
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
