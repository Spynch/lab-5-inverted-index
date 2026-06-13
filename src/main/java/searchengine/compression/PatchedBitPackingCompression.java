package searchengine.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class PatchedBitPackingCompression implements IntCompressor {
    private static final int BLOCK_SIZE = 128;
    private static final double PACKED_PERCENTILE = 0.90;

    @Override
    public byte[] compress(int[] values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(values.length);
                for (int offset = 0; offset < values.length; offset += BLOCK_SIZE) {
                    int count = Math.min(BLOCK_SIZE, values.length - offset);
                    int bitWidth = chooseBitWidth(values, offset, count);
                    int[] packedValues = new int[count];
                    int exceptionCount = 0;
                    long limit = bitWidth == 32 ? 0x1_0000_0000L : 1L << bitWidth;
                    for (int i = 0; i < count; i++) {
                        int value = values[offset + i];
                        if (value < 0) {
                            throw new IllegalArgumentException("Patched bitpacking supports only non-negative integers");
                        }
                        if (Integer.toUnsignedLong(value) >= limit) {
                            exceptionCount++;
                        } else {
                            packedValues[i] = value;
                        }
                    }

                    byte[] packed = packWithWidth(packedValues, bitWidth);
                    out.writeShort(count);
                    out.writeByte(bitWidth);
                    out.writeInt(packed.length);
                    out.write(packed);
                    out.writeShort(exceptionCount);
                    for (int i = 0; i < count; i++) {
                        int value = values[offset + i];
                        if (Integer.toUnsignedLong(value) >= limit) {
                            out.writeShort(i);
                            out.writeInt(value);
                        }
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected in-memory compression failure", e);
        }
    }

    @Override
    public int[] decompress(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int length = in.readInt();
            if (length < 0) {
                throw new IllegalArgumentException("Negative value count");
            }
            int[] values = new int[length];
            int offset = 0;
            while (offset < length) {
                int count = in.readUnsignedShort();
                int bitWidth = in.readUnsignedByte();
                int packedLength = in.readInt();
                if (count <= 0 || count > BLOCK_SIZE || bitWidth < 1 || bitWidth > 32 || packedLength < 0) {
                    throw new IllegalArgumentException("Corrupted patched bitpacking block");
                }
                byte[] packed = new byte[packedLength];
                in.readFully(packed);
                int[] block = unpackWithWidth(packed, count, bitWidth);
                int exceptionCount = in.readUnsignedShort();
                for (int i = 0; i < exceptionCount; i++) {
                    int index = in.readUnsignedShort();
                    if (index >= count) {
                        throw new IllegalArgumentException("Corrupted patched bitpacking exception");
                    }
                    block[index] = in.readInt();
                }
                if (offset + count > length) {
                    throw new IllegalArgumentException("Patched bitpacking block exceeds declared length");
                }
                System.arraycopy(block, 0, values, offset, count);
                offset += count;
            }
            return values;
        } catch (IOException e) {
            throw new IllegalArgumentException("Truncated patched bitpacking stream", e);
        }
    }

    private static int chooseBitWidth(int[] values, int offset, int count) {
        int[] widths = new int[count];
        for (int i = 0; i < count; i++) {
            int value = values[offset + i];
            if (value < 0) {
                throw new IllegalArgumentException("Patched bitpacking supports only non-negative integers");
            }
            widths[i] = value == 0 ? 1 : Integer.SIZE - Integer.numberOfLeadingZeros(value);
        }
        Arrays.sort(widths);
        int percentileIndex = Math.min(count - 1, (int) Math.ceil(count * PACKED_PERCENTILE) - 1);
        return widths[percentileIndex];
    }

    private static byte[] packWithWidth(int[] values, int bitWidth) {
        int payloadLength = (int) (((long) values.length * bitWidth + 7) / 8);
        byte[] payload = new byte[payloadLength];
        long buffer = 0L;
        int bits = 0;
        int output = 0;
        for (int value : values) {
            buffer |= Integer.toUnsignedLong(value) << bits;
            bits += bitWidth;
            while (bits >= 8) {
                payload[output++] = (byte) buffer;
                buffer >>>= 8;
                bits -= 8;
            }
        }
        if (bits > 0) {
            payload[output] = (byte) buffer;
        }
        return payload;
    }

    private static int[] unpackWithWidth(byte[] payload, int count, int bitWidth) {
        int[] values = new int[count];
        long buffer = 0L;
        int bits = 0;
        int input = 0;
        long mask = bitWidth == 32 ? 0xFFFF_FFFFL : (1L << bitWidth) - 1L;
        for (int i = 0; i < count; i++) {
            while (bits < bitWidth) {
                if (input >= payload.length) {
                    throw new IllegalArgumentException("Truncated patched bitpacking payload");
                }
                buffer |= (long) (payload[input++] & 0xFF) << bits;
                bits += 8;
            }
            values[i] = (int) (buffer & mask);
            buffer >>>= bitWidth;
            bits -= bitWidth;
        }
        return values;
    }
}
