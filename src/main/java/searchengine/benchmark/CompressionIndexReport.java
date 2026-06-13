package searchengine.benchmark;

import searchengine.compression.CompressionAnalyzer;
import searchengine.compression.IndexCompressionMode;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.storage.DiskIndexStats;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.DiskSearchEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class CompressionIndexReport {
    private CompressionIndexReport() {
    }

    public static void main(String[] args) throws Exception {
        Path outputRoot = args.length == 0 ? Path.of("target", "compression-report") : Path.of(args[0]);
        Files.createDirectories(outputRoot);
        InMemoryInvertedIndex index = BenchmarkCorpusFactory.buildIndex(10_000, 80);

        CompressionAnalyzer.printReport(index, System.out);
        System.out.println("mode, index_bytes, write_ms, cold_query_ms, warm_query_us");
        for (IndexCompressionMode mode : IndexCompressionMode.values()) {
            Path directory = outputRoot.resolve(mode.getId());
            long started = System.nanoTime();
            DiskIndexStats stats = new DiskIndexWriter(mode).write(index, directory);
            double writeMs = (System.nanoTime() - started) / 1_000_000.0;
            double coldMs;
            double warmMicros;
            try (DiskSearchEngine engine = DiskSearchEngine.mmap(directory)) {
                started = System.nanoTime();
                engine.search("(common AND medium1) OR (term42 NEAR/4 common)", 10);
                coldMs = (System.nanoTime() - started) / 1_000_000.0;
                int repetitions = 20;
                started = System.nanoTime();
                for (int i = 0; i < repetitions; i++) {
                    engine.search("(common AND medium1) OR (term42 NEAR/4 common)", 10);
                }
                warmMicros = (System.nanoTime() - started) / 1_000.0 / repetitions;
            }
            System.out.printf(Locale.ROOT, "%s, %d, %.3f, %.3f, %.3f%n",
                    mode.getId(), stats.getTotalBytes(), writeMs, coldMs, warmMicros);
        }
    }
}
