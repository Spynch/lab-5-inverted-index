package searchengine.compression;

import java.nio.ByteBuffer;

public final class BitPackingCompression implements IntCompressor {
    @Override
    public byte[] compress(int[] values) {
        int max = 0;
        for (int value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("BitPackingCompression supports only non-negative integers");
            }
            max = Math.max(max, value);
        }
        int bitWidth = max == 0 ? 1 : Integer.SIZE - Integer.numberOfLeadingZeros(max);
        long payloadBits = (long) values.length * bitWidth;
        if (payloadBits > (long) Integer.MAX_VALUE * 8L) {
            throw new IllegalArgumentException("Bit-packed payload is too large");
        }
        byte[] payload = new byte[(int) ((payloadBits + 7) / 8)];
        long bitBuffer = 0L;
        int bitsInBuffer = 0;
        int byteIndex = 0;
        for (int value : values) {
            bitBuffer |= Integer.toUnsignedLong(value) << bitsInBuffer;
            bitsInBuffer += bitWidth;
            while (bitsInBuffer >= 8) {
                payload[byteIndex++] = (byte) bitBuffer;
                bitBuffer >>>= 8;
                bitsInBuffer -= 8;
            }
        }
        if (bitsInBuffer > 0) {
            payload[byteIndex] = (byte) bitBuffer;
        }
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + 1 + payload.length);
        buffer.putInt(values.length);
        buffer.put((byte) bitWidth);
        buffer.put(payload);
        return buffer.array();
    }

    @Override
    public int[] decompress(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int length = buffer.getInt();
        int bitWidth = buffer.get() & 0xFF;
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        if (bitWidth < 1 || bitWidth > 32) {
            throw new IllegalArgumentException("Invalid bit width: " + bitWidth);
        }
        int[] values = new int[length];
        long bitBuffer = 0L;
        int bitsInBuffer = 0;
        int byteIndex = 0;
        long mask = bitWidth == 32 ? 0xFFFF_FFFFL : (1L << bitWidth) - 1L;
        for (int i = 0; i < length; i++) {
            while (bitsInBuffer < bitWidth) {
                if (byteIndex >= payload.length) {
                    throw new IllegalArgumentException("Truncated bit-packed stream");
                }
                bitBuffer |= (long) (payload[byteIndex++] & 0xFF) << bitsInBuffer;
                bitsInBuffer += 8;
            }
            values[i] = (int) (bitBuffer & mask);
            bitBuffer >>>= bitWidth;
            bitsInBuffer -= bitWidth;
        }
        return values;
    }
}
