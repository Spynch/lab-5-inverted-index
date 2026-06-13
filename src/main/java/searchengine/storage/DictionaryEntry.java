package searchengine.storage;

public final class DictionaryEntry {
    private final String term;
    private final int documentFrequency;
    private final long postingsOffset;
    private final int postingsLength;
    private final long positionsOffset;
    private final int positionsLength;

    public DictionaryEntry(String term, int documentFrequency, long postingsOffset, int postingsLength,
                           long positionsOffset, int positionsLength) {
        this.term = term;
        this.documentFrequency = documentFrequency;
        this.postingsOffset = postingsOffset;
        this.postingsLength = postingsLength;
        this.positionsOffset = positionsOffset;
        this.positionsLength = positionsLength;
    }

    public String getTerm() {
        return term;
    }

    public int getDocumentFrequency() {
        return documentFrequency;
    }

    public long getPostingsOffset() {
        return postingsOffset;
    }

    public int getPostingsLength() {
        return postingsLength;
    }

    public long getPositionsOffset() {
        return positionsOffset;
    }

    public int getPositionsLength() {
        return positionsLength;
    }
}