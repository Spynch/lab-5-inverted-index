package searchengine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class PagedMMapFile implements AutoCloseable {
    static final int DEFAULT_PAGE_SIZE = 64 * 1024;
    static final int DEFAULT_MAX_PAGES = 64;

    private final FileChannel channel;
    private final int pageSize;
    private final long fileSize;
    private final Map<Long, MappedByteBuffer> pages;

    PagedMMapFile(Path path) throws IOException {
        this(path, DEFAULT_PAGE_SIZE, DEFAULT_MAX_PAGES);
    }

    PagedMMapFile(Path path, int pageSize, int maxPages) throws IOException {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.pageSize = Math.max(4096, pageSize);
        this.fileSize = channel.size();
        this.pages = new LinkedHashMap<Long, MappedByteBuffer>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, MappedByteBuffer> eldest) {
                return size() > maxPages;
            }
        };
    }

    ByteBuffer readSlice(long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > fileSize) {
            throw new IOException("Requested mmap slice is outside file bounds");
        }
        if (length == 0) {
            return ByteBuffer.allocate(0);
        }

        long firstPage = offset / pageSize;
        long lastPage = (offset + length - 1L) / pageSize;
        if (firstPage == lastPage) {
            ByteBuffer page = page(firstPage).duplicate();
            int pageOffset = (int) (offset % pageSize);
            page.position(pageOffset);
            page.limit(pageOffset + length);
            return page.slice().asReadOnlyBuffer();
        }

        ByteBuffer result = ByteBuffer.allocate(length);
        long position = offset;
        int remaining = length;
        while (remaining > 0) {
            long pageIndex = position / pageSize;
            int pageOffset = (int) (position % pageSize);
            ByteBuffer page = page(pageIndex).duplicate();
            int bytes = Math.min(remaining, page.limit() - pageOffset);
            page.position(pageOffset);
            page.limit(pageOffset + bytes);
            result.put(page);
            position += bytes;
            remaining -= bytes;
        }
        result.flip();
        return result;
    }

    private synchronized MappedByteBuffer page(long pageIndex) throws IOException {
        MappedByteBuffer mapped = pages.get(pageIndex);
        if (mapped != null) {
            return mapped;
        }
        long offset = pageIndex * pageSize;
        long length = Math.min(pageSize, fileSize - offset);
        if (length <= 0) {
            throw new IOException("Requested mmap page is outside file bounds");
        }
        MappedByteBuffer next = channel.map(FileChannel.MapMode.READ_ONLY, offset, length);
        pages.put(pageIndex, next);
        return next;
    }

    @Override
    public void close() throws IOException {
        pages.clear();
        channel.close();
    }
}
