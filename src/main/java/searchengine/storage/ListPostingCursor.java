package searchengine.storage;

import searchengine.index.Posting;
import searchengine.index.PostingList;

final class ListPostingCursor implements PostingCursor {
    private final PostingList postingList;
    private int index = -1;

    ListPostingCursor(PostingList postingList) {
        this.postingList = postingList;
    }

    @Override
    public boolean next() {
        if (index + 1 >= postingList.size()) {
            return false;
        }
        index++;
        return true;
    }

    @Override
    public boolean isPositioned() {
        return index >= 0 && index < postingList.size();
    }

    @Override
    public int docId() {
        return current().getDocId();
    }

    @Override
    public int termFrequency() {
        return current().getTermFrequency();
    }

    @Override
    public int[] positions() {
        return current().getPositions();
    }

    @Override
    public boolean advanceTo(int targetDocId) {
        if (isPositioned() && docId() >= targetDocId) {
            return true;
        }
        int low = Math.max(0, index + 1);
        int high = postingList.size() - 1;
        int result = postingList.size();
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (postingList.get(middle).getDocId() >= targetDocId) {
                result = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        if (result >= postingList.size()) {
            index = postingList.size();
            return false;
        }
        index = result;
        return true;
    }

    private Posting current() {
        if (!isPositioned()) {
            throw new IllegalStateException("Cursor is not positioned");
        }
        return postingList.get(index);
    }
}
