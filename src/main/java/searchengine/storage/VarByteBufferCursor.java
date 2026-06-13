package searchengine.storage;

import java.nio.ByteBuffer;

final class VarByteBufferCursor {
    private final ByteBuffer buffer;
    private final int length;
    private int read;

    VarByteBufferCursor(ByteBuffer buffer) {
        this.buffer = buffer.slice();
        this.length = readVarInt(this.buffer);
    }

    int length() {
        return length;
    }

    int position() {
        return buffer.position();
    }

    int readCount() {
        return read;
    }

    void setState(int position, int read) {
        if (read < 0 || read > length) {
            throw new IllegalArgumentException("Invalid varbyte read count");
        }
        buffer.position(position);
        this.read = read;
    }

    boolean hasNext() {
        return read < length;
    }

    int next() {
        if (!hasNext()) {
            throw new IllegalStateException("No more varbyte values");
        }
        read++;
        return readVarInt(buffer);
    }

    private static int readVarInt(ByteBuffer buffer) {
        int shift = 0;
        int result = 0;
        while (buffer.hasRemaining()) {
            int b = buffer.get() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Truncated varbyte stream");
    }
}
