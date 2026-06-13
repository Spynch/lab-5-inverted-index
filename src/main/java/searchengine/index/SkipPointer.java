package searchengine.index;

public final class SkipPointer {
    private final int fromIndex;
    private final int toIndex;
    private final int targetDocId;

    public SkipPointer(int fromIndex, int toIndex, int targetDocId) {
        this.fromIndex = fromIndex;
        this.toIndex = toIndex;
        this.targetDocId = targetDocId;
    }

    public int getFromIndex() {
        return fromIndex;
    }

    public int getToIndex() {
        return toIndex;
    }

    public int getTargetDocId() {
        return targetDocId;
    }
}
