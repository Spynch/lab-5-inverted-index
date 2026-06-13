package searchengine.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DocIdSet {
    private final int[] docIds;

    public DocIdSet(List<Integer> docIds) {
        this.docIds = docIds.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    public List<Integer> getDocIds() {
        List<Integer> result = new ArrayList<>(docIds.length);
        for (int docId : docIds) {
            result.add(docId);
        }
        return Collections.unmodifiableList(result);
    }

    public int size() {
        return docIds.length;
    }

    public int get(int index) {
        return docIds[index];
    }
}
