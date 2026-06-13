package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.index.DocIdSet;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.index.PostingListOperations;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PostingListOperationsTest {
    @Test
    void intersectsWithTwoPointers() {
        PostingList a = list("a", 1, 3, 5, 9);
        PostingList b = list("b", 3, 4, 5, 10);

        assertEquals(Arrays.asList(3, 5), docIds(PostingListOperations.andWithoutSkips(a, b)));
    }

    @Test
    void intersectsWithSkips() {
        PostingList a = list("a", 1, 3, 5, 7, 9, 11, 13, 15, 17);
        PostingList b = list("b", 2, 4, 9, 13, 19);

        assertFalse(a.getSkipPointers().isEmpty());
        assertEquals(Arrays.asList(9, 13), docIds(PostingListOperations.andWithSkips(a, b)));
    }

    @Test
    void unionsPostingLists() {
        PostingList a = new PostingList("a", Arrays.asList(
                new Posting(1, 1, new int[] {0}),
                new Posting(3, 1, new int[] {2}),
                new Posting(5, 1, new int[] {4})
        ));
        PostingList b = new PostingList("b", Arrays.asList(
                new Posting(3, 2, new int[] {1, 3}),
                new Posting(4, 1, new int[] {0})
        ));

        PostingList result = PostingListOperations.or(a, b, true);

        assertEquals(Arrays.asList(1, 3, 4, 5), docIds(result));
        assertArrayEquals(new int[] {1, 2, 3}, result.get(1).getPositions());
    }

    @Test
    void intersectionPreservesPositionsFromBothBranches() {
        PostingList a = new PostingList("a", Arrays.asList(new Posting(3, 1, new int[] {2})));
        PostingList b = new PostingList("b", Arrays.asList(new Posting(3, 2, new int[] {1, 3})));

        PostingList result = PostingListOperations.andWithSkips(a, b, true);

        assertArrayEquals(new int[] {1, 2, 3}, result.get(0).getPositions());
    }

    @Test
    void subtractsPostingLists() {
        PostingList a = list("a", 1, 3, 5, 9);
        PostingList b = list("b", 3, 9);

        assertEquals(Arrays.asList(1, 5), docIds(PostingListOperations.not(a, b, new DocIdSet(Arrays.asList(1, 3, 5, 9)))));
    }

    @Test
    void complementsAgainstUniverse() {
        PostingList b = list("b", 3, 9);

        assertEquals(Arrays.asList(1, 5), docIds(PostingListOperations.not(PostingList.empty("*"), b, new DocIdSet(Arrays.asList(1, 3, 5, 9)))));
    }

    @Test
    void findsAdjacentPositions() {
        PostingList newList = new PostingList("new", Arrays.asList(new Posting(1, 1, new int[] {0}), new Posting(2, 1, new int[] {0})));
        PostingList yorkList = new PostingList("york", Arrays.asList(new Posting(1, 1, new int[] {1}), new Posting(2, 1, new int[] {2})));

        PostingList result = PostingListOperations.adjacent(newList, yorkList);

        assertEquals(Arrays.asList(1), docIds(result));
        assertArrayEquals(new int[] {1}, result.getPostings().get(0).getPositions());
    }

    @Test
    void findsNearPositionsWithoutCartesianProduct() {
        PostingList newList = new PostingList("new", Arrays.asList(new Posting(1, 1, new int[] {0}), new Posting(2, 1, new int[] {0})));
        PostingList yorkList = new PostingList("york", Arrays.asList(new Posting(1, 1, new int[] {1}), new Posting(2, 1, new int[] {2})));

        assertEquals(Arrays.asList(1, 2), docIds(PostingListOperations.near(newList, yorkList, 2)));
    }

    private static PostingList list(String term, int... docIds) {
        return new PostingList(term, Arrays.stream(docIds)
                .mapToObj(docId -> new Posting(docId, 1, new int[] {0}))
                .collect(Collectors.toList()));
    }

    private static List<Integer> docIds(PostingList postingList) {
        return postingList.getPostings().stream().map(Posting::getDocId).collect(Collectors.toList());
    }
}
