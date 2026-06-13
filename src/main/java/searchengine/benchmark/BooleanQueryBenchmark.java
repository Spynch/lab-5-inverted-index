package searchengine.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.index.PostingListOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class BooleanQueryBenchmark {
    @Benchmark
    public PostingList andWithSkips(Data data) {
        return PostingListOperations.andWithSkips(data.left, data.right);
    }

    @Benchmark
    public PostingList andWithoutSkips(Data data) {
        return PostingListOperations.andWithoutSkips(data.left, data.right);
    }

    @Benchmark
    public PostingList or(Data data) {
        return PostingListOperations.or(data.left, data.right);
    }

    @Benchmark
    public PostingList andNot(Data data) {
        return PostingListOperations.andNot(data.left, data.right);
    }

    @Benchmark
    public PostingList adjacent(Data data) {
        return PostingListOperations.adjacent(data.left, data.right);
    }

    @Benchmark
    public PostingList near(Data data) {
        return PostingListOperations.near(data.left, data.right, 3);
    }

    @State(Scope.Thread)
    public static class Data {
        private static final int UNIVERSE = 100_000;

        @Param({"rare-rare", "rare-medium", "rare-common", "medium-medium", "common-common"})
        public String pair;

        private PostingList left;
        private PostingList right;

        @Setup(Level.Trial)
        public void setup() {
            int[] sizes = sizes(pair);
            int overlap = Math.max(1, Math.min(sizes[0], sizes[1]) / 4);
            int[] leftDocIds = evenlySpaced(sizes[0], 0);
            int[] rightDocIds = withControlledOverlap(sizes[1], leftDocIds, overlap);
            left = build("left-" + pair, leftDocIds, new int[] {0, 10});
            right = build("right-" + pair, rightDocIds, new int[] {1, 12});
        }

        private static int[] sizes(String pair) {
            switch (pair) {
                case "rare-rare":
                    return new int[] {100, 100};
                case "rare-medium":
                    return new int[] {100, 5_000};
                case "rare-common":
                    return new int[] {100, 50_000};
                case "medium-medium":
                    return new int[] {5_000, 5_000};
                case "common-common":
                    return new int[] {50_000, 50_000};
                default:
                    throw new IllegalArgumentException("Unknown pair: " + pair);
            }
        }

        private static int[] evenlySpaced(int size, int phase) {
            int[] values = new int[size];
            for (int i = 0; i < size; i++) {
                values[i] = 1 + (int) (((long) i * UNIVERSE / size + phase) % UNIVERSE);
            }
            java.util.Arrays.sort(values);
            return values;
        }

        private static int[] withControlledOverlap(int size, int[] left, int overlap) {
            TreeSet<Integer> values = new TreeSet<>();
            for (int i = 0; i < overlap; i++) {
                values.add(left[i * left.length / overlap]);
            }
            int candidate = 2;
            int stride = 7918;
            while (values.size() < size) {
                values.add(candidate);
                candidate = 1 + (candidate + stride) % UNIVERSE;
            }
            return values.stream().mapToInt(Integer::intValue).toArray();
        }

        private static PostingList build(String term, int[] docIds, int[] positions) {
            List<Posting> postings = new ArrayList<>(docIds.length);
            for (int docId : docIds) {
                postings.add(new Posting(docId, positions.length, positions));
            }
            return new PostingList(term, postings);
        }
    }
}
