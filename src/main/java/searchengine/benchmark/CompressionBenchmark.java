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
import searchengine.compression.IndexCompressionMode;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class CompressionBenchmark {
    @Benchmark
    public byte[] compress(Data data) {
        return data.encode();
    }

    @Benchmark
    public int[] decompress(Data data) {
        return data.decode();
    }

    @State(Scope.Thread)
    public static class Data {
        @Param({"none", "delta", "varbyte", "delta-varbyte", "bitpacking",
                "delta-bitpacking", "pfor", "delta-pfor"})
        public String compression;

        @Param({"docIds", "termFrequencies", "positionGaps"})
        public String valueKind;

        private IndexCompressionMode mode;
        private int[] values;
        private byte[] compressed;

        @Setup(Level.Trial)
        public void setup() {
            mode = IndexCompressionMode.fromId(compression);
            values = values(valueKind, 100_000);
            compressed = encode();
        }

        byte[] encode() {
            return "docIds".equals(valueKind) ? mode.encodeDocIds(values) : mode.encodeValues(values);
        }

        int[] decode() {
            return "docIds".equals(valueKind) ? mode.decodeDocIds(compressed) : mode.decodeValues(compressed);
        }

        private static int[] values(String kind, int count) {
            int[] values = new int[count];
            if ("docIds".equals(kind)) {
                int current = 0;
                for (int i = 0; i < count; i++) {
                    current += 1 + (i % 7);
                    values[i] = current;
                }
            } else if ("termFrequencies".equals(kind)) {
                for (int i = 0; i < count; i++) {
                    values[i] = 1 + (i * 17 % 12);
                }
            } else if ("positionGaps".equals(kind)) {
                for (int i = 0; i < count; i++) {
                    values[i] = i % 31 == 0 ? 200 + i % 1000 : 1 + i % 5;
                }
            } else {
                throw new IllegalArgumentException("Unknown value kind: " + kind);
            }
            return values;
        }
    }
}
