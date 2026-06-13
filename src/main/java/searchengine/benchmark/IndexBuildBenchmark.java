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
import org.openjdk.jmh.annotations.Warmup;
import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(2)
public class IndexBuildBenchmark {
    @Benchmark
    public InMemoryInvertedIndex build(Data data) {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(data.documents);
        return index;
    }

    @State(Scope.Thread)
    public static class Data {
        private List<Document> documents;

        @Setup(Level.Trial)
        public void setup() {
            documents = new ArrayList<>();
            for (int docId = 1; docId <= 10_000; docId++) {
                StringBuilder text = new StringBuilder();
                for (int position = 0; position < 80; position++) {
                    text.append(position % 5 == 0 ? "common" : "term" + ((docId + position) % 4096)).append(' ');
                }
                documents.add(new Document(docId, "doc-" + docId, text.toString()));
            }
        }
    }
}
