package searchengine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public final class MMapIndexReader extends AbstractDiskIndexReader {
    private final PagedMMapFile postingsFile;
    private final PagedMMapFile positionsFile;

    public MMapIndexReader(Path directory) throws IOException {
        this(directory, PagedMMapFile.DEFAULT_PAGE_SIZE, PagedMMapFile.DEFAULT_MAX_PAGES);
    }

    public MMapIndexReader(Path directory, int pageSize, int maxPages) throws IOException {
        super(directory);
        this.postingsFile = new PagedMMapFile(DiskIndexFiles.postings(directory), pageSize, maxPages);
        this.positionsFile = new PagedMMapFile(DiskIndexFiles.positions(directory), pageSize, maxPages);
    }

    @Override
    protected ByteBuffer readPostingsSlice(DictionaryEntry entry) throws IOException {
        return postingsFile.readSlice(entry.getPostingsOffset(), entry.getPostingsLength());
    }

    @Override
    protected ByteBuffer readPositionsSlice(DictionaryEntry entry) throws IOException {
        return positionsFile.readSlice(entry.getPositionsOffset(), entry.getPositionsLength());
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            postingsFile.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            positionsFile.close();
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
}
