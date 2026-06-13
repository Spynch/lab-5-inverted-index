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
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.PostingList;
import searchengine.query.AntlrQueryParser;
import searchengine.query.QueryExecutor;
import searchengine.query.QueryNode;
import searchengine.ranking.BM25Scorer;
import searchengine.ranking.SearchResult;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class QueryPipelineBenchmark {
    @Benchmark
    public QueryNode parse(Data data) {
        return data.parser.parse(data.query);
    }

    @Benchmark
    public PostingList execute(Data data) {
        return data.executor.execute(data.parsed);
    }

    @Benchmark
    public List<SearchResult> rank(Data data) {
        return data.scorer.rank(data.candidates, data.parsed.positiveTerms(), 10);
    }

    @State(Scope.Thread)
    public static class Data {
        private InMemoryInvertedIndex index;
        private AntlrQueryParser parser;
        private QueryExecutor executor;
        private BM25Scorer scorer;
        private String query;
        private QueryNode parsed;
        private PostingList candidates;

        @Setup(Level.Trial)
        public void setup() {
            index = BenchmarkCorpusFactory.buildIndex(20_000, 80);
            parser = new AntlrQueryParser();
            executor = new QueryExecutor(index);
            scorer = new BM25Scorer(index);
            query = "(common AND medium1) OR (term42 NEAR/4 common)";
            parsed = parser.parse(query);
            candidates = executor.execute(parsed);
        }
    }
}
