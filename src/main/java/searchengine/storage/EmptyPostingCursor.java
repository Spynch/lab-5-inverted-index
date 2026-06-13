package searchengine.storage;

final class EmptyPostingCursor implements PostingCursor {
    static final EmptyPostingCursor INSTANCE = new EmptyPostingCursor();

    private EmptyPostingCursor() {
    }

    @Override
    public boolean next() {
        return false;
    }

    @Override
    public boolean isPositioned() {
        return false;
    }

    @Override
    public int docId() {
        throw new IllegalStateException("Cursor is not positioned");
    }

    @Override
    public int termFrequency() {
        throw new IllegalStateException("Cursor is not positioned");
    }

    @Override
    public int[] positions() {
        throw new IllegalStateException("Cursor is not positioned");
    }
}
