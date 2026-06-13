package searchengine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DiskIndexReader extends AbstractDiskIndexReader {
    private final FileChannel postingsChannel;
    private final FileChannel positionsChannel;

    public DiskIndexReader(Path directory) throws IOException {
        super(directory);
        this.postingsChannel = FileChannel.open(DiskIndexFiles.postings(directory), StandardOpenOption.READ);
        this.positionsChannel = FileChannel.open(DiskIndexFiles.positions(directory), StandardOpenOption.READ);
    }

    @Override
    protected ByteBuffer readPostingsSlice(DictionaryEntry entry) throws IOException {
        return readSlice(postingsChannel, entry.getPostingsOffset(), entry.getPostingsLength());
    }

    @Override
    protected ByteBuffer readPositionsSlice(DictionaryEntry entry) throws IOException {
        return readSlice(positionsChannel, entry.getPositionsOffset(), entry.getPositionsLength());
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            postingsChannel.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            positionsChannel.close();
        } catch (IOException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static ByteBuffer readSlice(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading disk index slice");
            }
            position += read;
        }
        buffer.flip();
        return buffer;
    }
}