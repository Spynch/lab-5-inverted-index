package searchengine.compression;

import java.util.Locale;

public enum IndexCompressionMode {
    NONE("none", false, new NoCompression()),
    DELTA("delta", true, new NoCompression()),
    VARBYTE("varbyte", false, new VarByteCompression()),
    DELTA_VARBYTE("delta-varbyte", true, new VarByteCompression()),
    BITPACKING("bitpacking", false, new BitPackingCompression()),
    DELTA_BITPACKING("delta-bitpacking", true, new BitPackingCompression()),
    PFOR("pfor", false, new PatchedBitPackingCompression()),
    DELTA_PFOR("delta-pfor", true, new PatchedBitPackingCompression());

    private final String id;
    private final boolean deltaEncoded;
    private final IntCompressor compressor;

    IndexCompressionMode(String id, boolean deltaEncoded, IntCompressor compressor) {
        this.id = id;
        this.deltaEncoded = deltaEncoded;
        this.compressor = compressor;
    }

    public String getId() {
        return id;
    }

    public boolean usesDeltaEncoding() {
        return deltaEncoded;
    }

    public byte[] encodeDocIds(int[] docIds) {
        return compressor.compress(deltaEncoded ? toDeltas(docIds) : docIds);
    }

    public int[] decodeDocIds(byte[] bytes) {
        int[] values = compressor.decompress(bytes);
        return deltaEncoded ? fromDeltas(values) : values;
    }

    public byte[] encodeValues(int[] values) {
        return compressor.compress(values);
    }

    public int[] decodeValues(byte[] bytes) {
        return compressor.decompress(bytes);
    }

    public static IndexCompressionMode fromId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        for (IndexCompressionMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown compression mode: " + id);
    }

    private static int[] toDeltas(int[] values) {
        int[] deltas = new int[values.length];
        int previous = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] < previous) {
                throw new IllegalArgumentException("Delta encoding requires sorted non-decreasing values");
            }
            deltas[i] = values[i] - previous;
            previous = values[i];
        }
        return deltas;
    }

    private static int[] fromDeltas(int[] deltas) {
        int[] values = new int[deltas.length];
        int previous = 0;
        for (int i = 0; i < deltas.length; i++) {
            previous += deltas[i];
            values[i] = previous;
        }
        return values;
    }
}
