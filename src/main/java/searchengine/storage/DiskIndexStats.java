package searchengine.storage;

public final class DiskIndexStats {
    private final long dictionaryBytes;
    private final long postingsBytes;
    private final long positionsBytes;
    private final long documentsBytes;
    private final long metaBytes;

    public DiskIndexStats(long dictionaryBytes, long postingsBytes, long positionsBytes, long documentsBytes, long metaBytes) {
        this.dictionaryBytes = dictionaryBytes;
        this.postingsBytes = postingsBytes;
        this.positionsBytes = positionsBytes;
        this.documentsBytes = documentsBytes;
        this.metaBytes = metaBytes;
    }

    public long getDictionaryBytes() {
        return dictionaryBytes;
    }

    public long getPostingsBytes() {
        return postingsBytes;
    }

    public long getPositionsBytes() {
        return positionsBytes;
    }

    public long getDocumentsBytes() {
        return documentsBytes;
    }

    public long getMetaBytes() {
        return metaBytes;
    }

    public long getTotalBytes() {
        return dictionaryBytes + postingsBytes + positionsBytes + documentsBytes + metaBytes;
    }
}