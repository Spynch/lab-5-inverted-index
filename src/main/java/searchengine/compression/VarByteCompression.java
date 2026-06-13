package searchengine.compression;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class VarByteCompression implements IntCompressor {
    @Override
    public byte[] compress(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, values.length);
        for (int value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("VarByteCompression supports only non-negative integers");
            }
            writeVarInt(out, value);
        }
        return out.toByteArray();
    }

    @Override
    public int[] decompress(byte[] bytes) {
        Cursor cursor = new Cursor();
        int length = readVarInt(bytes, cursor);
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = readVarInt(bytes, cursor);
        }
        return values;
    }

    static byte[] compressValues(int[] values) {
        return new VarByteCompression().compress(values);
    }

    static int[] decompressValues(byte[] bytes) {
        return new VarByteCompression().decompress(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private static int readVarInt(byte[] bytes, Cursor cursor) {
        int shift = 0;
        int result = 0;
        while (cursor.index < bytes.length) {
            int b = bytes[cursor.index++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Truncated varbyte stream");
    }

    private static final class Cursor {
        private int index;
    }
}
