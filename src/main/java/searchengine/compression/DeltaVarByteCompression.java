package searchengine.compression;

public final class DeltaVarByteCompression implements IntCompressor {
    private final VarByteCompression delegate = new VarByteCompression();

    @Override
    public byte[] compress(int[] values) {
        int[] deltas = toDeltas(values);
        return delegate.compress(deltas);
    }

    @Override
    public int[] decompress(byte[] bytes) {
        int[] deltas = delegate.decompress(bytes);
        return fromDeltas(deltas);
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
