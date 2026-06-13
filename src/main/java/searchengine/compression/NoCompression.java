package searchengine.compression;

import java.nio.ByteBuffer;

public final class NoCompression implements IntCompressor {
    @Override
    public byte[] compress(int[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + values.length * Integer.BYTES);
        buffer.putInt(values.length);
        for (int value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    @Override
    public int[] decompress(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int length = buffer.getInt();
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = buffer.getInt();
        }
        return values;
    }
}
