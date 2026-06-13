package searchengine.index;

import java.util.ArrayList;
import java.util.List;

public final class PostingListOperations {
    private PostingListOperations() {
    }

    public static PostingList andWithoutSkips(PostingList a, PostingList b) {
        return andWithoutSkips(a, b, false);
    }

    public static PostingList andWithoutSkips(PostingList a, PostingList b, boolean preserveAllPositions) {
        List<Posting> result = new ArrayList<>(Math.min(a.size(), b.size()));
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Posting left = a.get(i);
            Posting right = b.get(j);
            if (left.getDocId() == right.getDocId()) {
                result.add(preserveAllPositions
                        ? mergeForBooleanResult(left, right)
                        : copyForBooleanResult(left));
                i++;
                j++;
            } else if (left.getDocId() < right.getDocId()) {
                i++;
            } else {
                j++;
            }
        }
        return PostingList.trustedSorted(a.getTerm() + " AND " + b.getTerm(), result);
    }

    public static PostingList andWithSkips(PostingList a, PostingList b) {
        return andWithSkips(a, b, false);
    }

    public static PostingList andWithSkips(PostingList a, PostingList b, boolean preserveAllPositions) {
        if (!skipsLikelyUseful(a, b)) {
            return andWithoutSkips(a, b, preserveAllPositions);
        }
        List<Posting> result = new ArrayList<>(Math.min(a.size(), b.size()));
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Posting left = a.get(i);
            Posting right = b.get(j);
            if (left.getDocId() == right.getDocId()) {
                result.add(preserveAllPositions
                        ? mergeForBooleanResult(left, right)
                        : copyForBooleanResult(left));
                i++;
                j++;
            } else if (left.getDocId() < right.getDocId()) {
                i = advance(a, i, right.getDocId());
            } else {
                j = advance(b, j, left.getDocId());
            }
        }
        return PostingList.trustedSorted(a.getTerm() + " AND " + b.getTerm(), result);
    }

    private static boolean skipsLikelyUseful(PostingList a, PostingList b) {
        int smaller = Math.min(a.size(), b.size());
        int larger = Math.max(a.size(), b.size());
        return smaller >= 4 && (long) larger >= (long) smaller * 4L;
    }

    public static PostingList or(PostingList a, PostingList b) {
        return or(a, b, false);
    }

    public static PostingList or(PostingList a, PostingList b, boolean preserveAllPositions) {
        List<Posting> result = new ArrayList<>(a.size() + b.size());
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Posting left = a.get(i);
            Posting right = b.get(j);
            if (left.getDocId() == right.getDocId()) {
                result.add(preserveAllPositions
                        ? mergeForBooleanResult(left, right)
                        : copyForBooleanResult(left));
                i++;
                j++;
            } else if (left.getDocId() < right.getDocId()) {
                result.add(copyForBooleanResult(left));
                i++;
            } else {
                result.add(copyForBooleanResult(right));
                j++;
            }
        }
        while (i < a.size()) {
            result.add(copyForBooleanResult(a.get(i++)));
        }
        while (j < b.size()) {
            result.add(copyForBooleanResult(b.get(j++)));
        }
        return PostingList.trustedSorted(a.getTerm() + " OR " + b.getTerm(), result);
    }

    public static PostingList not(PostingList a, PostingList b, DocIdSet universe) {
        if (a != null && !a.isEmpty()) {
            return andNot(a, b);
        }
        List<Posting> result = new ArrayList<>(universe.size());
        int i = 0;
        int j = 0;
        while (i < universe.size()) {
            int docId = universe.get(i);
            j = advance(b, j, docId);
            if (j >= b.size() || b.get(j).getDocId() != docId) {
                result.add(Posting.docOnly(docId, 0));
            }
            i++;
        }
        return PostingList.trustedSorted("NOT " + b.getTerm(), result);
    }

    public static PostingList andNot(PostingList a, PostingList b) {
        List<Posting> result = new ArrayList<>(a.size());
        int i = 0;
        int j = 0;
        while (i < a.size()) {
            Posting left = a.get(i);
            j = advance(b, j, left.getDocId());
            if (j >= b.size() || b.get(j).getDocId() != left.getDocId()) {
                result.add(copyForBooleanResult(left));
            }
            i++;
        }
        return PostingList.trustedSorted(a.getTerm() + " AND NOT " + b.getTerm(), result);
    }

    public static PostingList adjacent(PostingList a, PostingList b) {
        return positional(a, b, 1, true, " ADJ ");
    }

    public static PostingList near(PostingList a, PostingList b, int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be non-negative");
        }
        return positional(a, b, k, false, " NEAR/" + k + " ");
    }

    private static int advance(PostingList list, int index, int targetDocId) {
        int current = index;
        while (current < list.size() && list.get(current).getDocId() < targetDocId) {
            SkipPointer skipPointer = list.skipFrom(current);
            if (skipPointer != null && skipPointer.getTargetDocId() <= targetDocId) {
                current = skipPointer.getToIndex();
            } else {
                current++;
            }
        }
        return current;
    }

    private static PostingList positional(PostingList a, PostingList b, int distance, boolean orderedAdjacent, String operator) {
        List<Posting> result = new ArrayList<>(Math.min(a.size(), b.size()));
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Posting left = a.get(i);
            Posting right = b.get(j);
            if (left.getDocId() == right.getDocId()) {
                int[] matches = orderedAdjacent
                        ? adjacentPositions(left.positionsView(), right.positionsView())
                        : nearPositions(left.positionsView(), right.positionsView(), distance);
                if (matches.length > 0) {
                    result.add(Posting.trusted(left.getDocId(), matches.length, matches));
                }
                i++;
                j++;
            } else if (left.getDocId() < right.getDocId()) {
                i = advance(a, i, right.getDocId());
            } else {
                j = advance(b, j, left.getDocId());
            }
        }
        return PostingList.trustedSorted(a.getTerm() + operator + b.getTerm(), result);
    }

    private static int[] adjacentPositions(int[] left, int[] right) {
        IntArrayBuilder matches = new IntArrayBuilder();
        int i = 0;
        int j = 0;
        while (i < left.length && j < right.length) {
            int expectedRight = left[i] + 1;
            if (right[j] == expectedRight) {
                matches.add(right[j]);
                i++;
                j++;
            } else if (right[j] < expectedRight) {
                j++;
            } else {
                i++;
            }
        }
        return matches.toArray();
    }

    private static int[] nearPositions(int[] left, int[] right, int k) {
        IntArrayBuilder matches = new IntArrayBuilder();
        int i = 0;
        int j = 0;
        while (i < left.length && j < right.length) {
            int diff = left[i] - right[j];
            int absDiff = Math.abs(diff);
            if (absDiff <= k) {
                matches.addIfDifferent(left[i]);
                if (left[i] <= right[j]) {
                    i++;
                } else {
                    j++;
                }
            } else if (left[i] < right[j]) {
                i++;
            } else {
                j++;
            }
        }
        return matches.toArray();
    }

    private static Posting copyForBooleanResult(Posting posting) {
        return Posting.trusted(posting.getDocId(), posting.getTermFrequency(), posting.positionsView());
    }

    private static Posting mergeForBooleanResult(Posting left, Posting right) {
        int[] positions = mergeSortedDistinct(left.positionsView(), right.positionsView());
        if (positions.length == 0) {
            return Posting.docOnly(left.getDocId(),
                    Math.max(left.getTermFrequency(), right.getTermFrequency()));
        }
        return Posting.trusted(left.getDocId(), positions.length, positions);
    }

    private static int[] mergeSortedDistinct(int[] left, int[] right) {
        if (left.length == 0) {
            return right;
        }
        if (right.length == 0) {
            return left;
        }
        IntArrayBuilder merged = new IntArrayBuilder(left.length + right.length);
        int i = 0;
        int j = 0;
        while (i < left.length && j < right.length) {
            if (left[i] == right[j]) {
                merged.addIfDifferent(left[i]);
                i++;
                j++;
            } else if (left[i] < right[j]) {
                merged.addIfDifferent(left[i++]);
            } else {
                merged.addIfDifferent(right[j++]);
            }
        }
        while (i < left.length) {
            merged.addIfDifferent(left[i++]);
        }
        while (j < right.length) {
            merged.addIfDifferent(right[j++]);
        }
        return merged.toArray();
    }

    private static final class IntArrayBuilder {
        private int[] values;
        private int size;

        private IntArrayBuilder() {
            this(4);
        }

        private IntArrayBuilder(int initialCapacity) {
            values = new int[Math.max(1, initialCapacity)];
        }

        void add(int value) {
            if (size == values.length) {
                int[] next = new int[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        void addIfDifferent(int value) {
            if (size == 0 || values[size - 1] != value) {
                add(value);
            }
        }

        int[] toArray() {
            int[] result = new int[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
