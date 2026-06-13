package searchengine.index;

import java.util.Arrays;

public final class Posting {
    private static final int[] EMPTY_POSITIONS = new int[0];

    private final int docId;
    private final int termFrequency;
    private final int[] positions;

    public Posting(int docId, int termFrequency, int[] positions) {
        this(docId, termFrequency, positions, true);
    }

    private Posting(int docId, int termFrequency, int[] positions, boolean copyPositions) {
        this.docId = docId;
        this.termFrequency = termFrequency;
        if (positions == null || positions.length == 0) {
            this.positions = EMPTY_POSITIONS;
        } else {
            this.positions = copyPositions ? Arrays.copyOf(positions, positions.length) : positions;
        }
    }

    public static Posting docOnly(int docId, int termFrequency) {
        return new Posting(docId, termFrequency, EMPTY_POSITIONS, false);
    }

    static Posting trusted(int docId, int termFrequency, int[] positions) {
        return new Posting(docId, termFrequency, positions, false);
    }

    public int getDocId() {
        return docId;
    }

    public int getTermFrequency() {
        return termFrequency;
    }

    public int[] getPositions() {
        return Arrays.copyOf(positions, positions.length);
    }

    int[] positionsView() {
        return positions;
    }
}
