package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.compression.BitPackingCompression;
import searchengine.compression.DeltaVarByteCompression;
import searchengine.compression.IntCompressor;
import searchengine.compression.IndexCompressionMode;
import searchengine.compression.NoCompression;
import searchengine.compression.PatchedBitPackingCompression;
import searchengine.compression.VarByteCompression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CompressionTest {
    @Test
    void roundTripsNoCompression() {
        assertRoundTrip(new NoCompression(), new int[] {10, 15, 21});
    }

    @Test
    void roundTripsVarByte() {
        assertRoundTrip(new VarByteCompression(), new int[] {0, 1, 127, 128, 16_384});
    }

    @Test
    void roundTripsDeltaVarByte() {
        assertRoundTrip(new DeltaVarByteCompression(), new int[] {10, 15, 21});
    }

    @Test
    void roundTripsBitPacking() {
        assertRoundTrip(new BitPackingCompression(), new int[] {0, 1, 3, 7, 15, 31, Integer.MAX_VALUE});
    }

    @Test
    void roundTripsPatchedBitPackingWithExceptions() {
        assertRoundTrip(new PatchedBitPackingCompression(),
                new int[] {1, 1, 2, 1, 3, 2, 1, 1_000_000, 2, 1, 2, 3});
    }

    @Test
    void allIndexCompressionModesRoundTripDocIdsAndUnsortedValues() {
        int[] docIds = {1, 2, 5, 100, 101, 10_000};
        int[] termFrequencies = {3, 1, 7, 2, 2, 9};
        for (IndexCompressionMode mode : IndexCompressionMode.values()) {
            assertArrayEquals(docIds, mode.decodeDocIds(mode.encodeDocIds(docIds)), mode.getId());
            assertArrayEquals(termFrequencies, mode.decodeValues(mode.encodeValues(termFrequencies)), mode.getId());
        }
    }

    private static void assertRoundTrip(IntCompressor compressor, int[] values) {
        assertArrayEquals(values, compressor.decompress(compressor.compress(values)));
    }
}
