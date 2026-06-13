package searchengine.storage;

import java.io.IOException;

public interface PostingCursor extends AutoCloseable {
    boolean next() throws IOException;

    boolean isPositioned();

    int docId();

    int termFrequency();

    int[] positions();

    default boolean advanceTo(int targetDocId) throws IOException {
        if (!isPositioned() && !next()) {
            return false;
        }
        while (docId() < targetDocId) {
            if (!next()) {
                return false;
            }
        }
        return true;
    }

    @Override
    default void close() throws IOException {
    }
}
