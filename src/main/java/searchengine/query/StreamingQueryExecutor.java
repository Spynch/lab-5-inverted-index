package searchengine.query;

import searchengine.index.DocIdSet;
import searchengine.storage.PostingCursor;
import searchengine.storage.PostingListReader;

import java.io.IOException;
import java.util.Objects;

public final class StreamingQueryExecutor {
    private static final int[] EMPTY_POSITIONS = new int[0];

    private final PostingListReader reader;
    private final DocIdSet universe;

    public StreamingQueryExecutor(PostingListReader reader, DocIdSet universe) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.universe = Objects.requireNonNull(universe, "universe");
    }

    public PostingCursor execute(QueryNode node) throws IOException {
        return execute(node, false);
    }

    private PostingCursor execute(QueryNode node, boolean positionsRequired) throws IOException {
        Objects.requireNonNull(node, "node");
        if (node instanceof TermNode) {
            return reader.openCursor(((TermNode) node).getTerm(), positionsRequired);
        }
        if (node instanceof AndNode) {
            AndNode and = (AndNode) node;
            if (and.getRight() instanceof NotNode && !(and.getLeft() instanceof NotNode)) {
                return new AndNotCursor(execute(and.getLeft(), positionsRequired),
                        execute(((NotNode) and.getRight()).getChild(), false));
            }
            if (and.getLeft() instanceof NotNode && !(and.getRight() instanceof NotNode)) {
                return new AndNotCursor(execute(and.getRight(), positionsRequired),
                        execute(((NotNode) and.getLeft()).getChild(), false));
            }
            return new AndCursor(execute(and.getLeft(), positionsRequired), execute(and.getRight(), positionsRequired));
        }
        if (node instanceof OrNode) {
            OrNode or = (OrNode) node;
            return new OrCursor(execute(or.getLeft(), positionsRequired), execute(or.getRight(), positionsRequired));
        }
        if (node instanceof NotNode) {
            return new NotCursor(universe, execute(((NotNode) node).getChild(), false));
        }
        if (node instanceof AdjNode) {
            AdjNode adj = (AdjNode) node;
            return new PositionalCursor(execute(adj.getLeft(), true), execute(adj.getRight(), true), 1, true);
        }
        if (node instanceof NearNode) {
            NearNode near = (NearNode) node;
            return new PositionalCursor(execute(near.getLeft(), true), execute(near.getRight(), true),
                    near.getDistance(), false);
        }
        throw new IllegalArgumentException("Unsupported query node: " + node.getClass().getName());
    }

    private abstract static class BaseCursor implements PostingCursor {
        int docId;
        int termFrequency;
        int[] positions = EMPTY_POSITIONS;
        boolean positioned;

        @Override
        public final boolean isPositioned() {
            return positioned;
        }

        @Override
        public final int docId() {
            requirePositioned();
            return docId;
        }

        @Override
        public final int termFrequency() {
            requirePositioned();
            return termFrequency;
        }

        @Override
        public final int[] positions() {
            requirePositioned();
            return positions;
        }

        final void setCurrent(int docId, int termFrequency, int[] positions) {
            this.docId = docId;
            this.termFrequency = termFrequency;
            this.positions = positions == null ? EMPTY_POSITIONS : positions;
            this.positioned = true;
        }

        private void requirePositioned() {
            if (!positioned) {
                throw new IllegalStateException("Cursor is not positioned");
            }
        }
    }

    private static final class AndCursor extends BaseCursor {
        private final PostingCursor left;
        private final PostingCursor right;
        private boolean advanceLeft;
        private boolean advanceRight;

        private AndCursor(PostingCursor left, PostingCursor right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean next() throws IOException {
            positioned = false;
            if (!prepare()) {
                return false;
            }
            while (true) {
                int leftDocId = left.docId();
                int rightDocId = right.docId();
                if (leftDocId == rightDocId) {
                    int[] mergedPositions = mergeSortedDistinct(left.positions(), right.positions());
                    int mergedFrequency = mergedPositions.length == 0
                            ? Math.max(left.termFrequency(), right.termFrequency())
                            : mergedPositions.length;
                    setCurrent(leftDocId, mergedFrequency, mergedPositions);
                    advanceLeft = true;
                    advanceRight = true;
                    return true;
                }
                if (leftDocId < rightDocId) {
                    if (!left.advanceTo(rightDocId)) {
                        return false;
                    }
                } else if (!right.advanceTo(leftDocId)) {
                    return false;
                }
            }
        }

        private boolean prepare() throws IOException {
            if (advanceLeft) {
                if (!left.next()) {
                    return false;
                }
                advanceLeft = false;
            } else if (!left.isPositioned() && !left.next()) {
                return false;
            }
            if (advanceRight) {
                if (!right.next()) {
                    return false;
                }
                advanceRight = false;
            } else if (!right.isPositioned() && !right.next()) {
                return false;
            }
            return true;
        }

        @Override
        public void close() throws IOException {
            closeBoth(left, right);
        }
    }

    private static final class AndNotCursor extends BaseCursor {
        private final PostingCursor included;
        private final PostingCursor excluded;
        private boolean advanceIncluded;

        private AndNotCursor(PostingCursor included, PostingCursor excluded) {
            this.included = included;
            this.excluded = excluded;
        }

        @Override
        public boolean next() throws IOException {
            positioned = false;
            if (advanceIncluded) {
                if (!included.next()) {
                    return false;
                }
                advanceIncluded = false;
            } else if (!included.isPositioned() && !included.next()) {
                return false;
            }
            while (true) {
                int candidate = included.docId();
                boolean excludedHasCandidate = excluded.advanceTo(candidate) && excluded.docId() == candidate;
                if (!excludedHasCandidate) {
                    setCurrent(candidate, included.termFrequency(), included.positions());
                    advanceIncluded = true;
                    return true;
                }
                if (!included.next()) {
                    return false;
                }
            }
        }

        @Override
        public void close() throws IOException {
            closeBoth(included, excluded);
        }
    }

    private static final class OrCursor extends BaseCursor {
        private final PostingCursor left;
        private final PostingCursor right;
        private boolean leftLive = true;
        private boolean rightLive = true;
        private boolean advanceLeft;
        private boolean advanceRight;

        private OrCursor(PostingCursor left, PostingCursor right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean next() throws IOException {
            positioned = false;
            prepare();
            if (!leftLive && !rightLive) {
                return false;
            }
            if (leftLive && rightLive) {
                int leftDocId = left.docId();
                int rightDocId = right.docId();
                if (leftDocId == rightDocId) {
                    int[] mergedPositions = mergeSortedDistinct(left.positions(), right.positions());
                    int mergedFrequency = mergedPositions.length == 0
                            ? Math.max(left.termFrequency(), right.termFrequency())
                            : mergedPositions.length;
                    setCurrent(leftDocId, mergedFrequency, mergedPositions);
                    advanceLeft = true;
                    advanceRight = true;
                } else if (leftDocId < rightDocId) {
                    setCurrent(leftDocId, left.termFrequency(), left.positions());
                    advanceLeft = true;
                } else {
                    setCurrent(rightDocId, right.termFrequency(), right.positions());
                    advanceRight = true;
                }
                return true;
            }
            if (leftLive) {
                setCurrent(left.docId(), left.termFrequency(), left.positions());
                advanceLeft = true;
                return true;
            }
            setCurrent(right.docId(), right.termFrequency(), right.positions());
            advanceRight = true;
            return true;
        }

        private void prepare() throws IOException {
            if (leftLive) {
                if (advanceLeft) {
                    leftLive = left.next();
                    advanceLeft = false;
                } else if (!left.isPositioned()) {
                    leftLive = left.next();
                }
            }
            if (rightLive) {
                if (advanceRight) {
                    rightLive = right.next();
                    advanceRight = false;
                } else if (!right.isPositioned()) {
                    rightLive = right.next();
                }
            }
        }

        @Override
        public void close() throws IOException {
            closeBoth(left, right);
        }
    }

    private static final class NotCursor extends BaseCursor {
        private final DocIdSet universe;
        private final PostingCursor excluded;
        private int universeIndex;

        private NotCursor(DocIdSet universe, PostingCursor excluded) {
            this.universe = universe;
            this.excluded = excluded;
        }

        @Override
        public boolean next() throws IOException {
            positioned = false;
            while (universeIndex < universe.size()) {
                int candidate = universe.get(universeIndex++);
                boolean excludedHasCandidate = excluded.advanceTo(candidate) && excluded.docId() == candidate;
                if (!excludedHasCandidate) {
                    setCurrent(candidate, 0, EMPTY_POSITIONS);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            excluded.close();
        }
    }

    private static final class PositionalCursor extends BaseCursor {
        private final PostingCursor left;
        private final PostingCursor right;
        private final int distance;
        private final boolean orderedAdjacent;
        private boolean advanceLeft;
        private boolean advanceRight;

        private PositionalCursor(PostingCursor left, PostingCursor right, int distance, boolean orderedAdjacent) {
            this.left = left;
            this.right = right;
            this.distance = distance;
            this.orderedAdjacent = orderedAdjacent;
        }

        @Override
        public boolean next() throws IOException {
            positioned = false;
            if (!prepare()) {
                return false;
            }
            while (true) {
                int leftDocId = left.docId();
                int rightDocId = right.docId();
                if (leftDocId == rightDocId) {
                    int[] matches = orderedAdjacent
                            ? adjacentPositions(left.positions(), right.positions())
                            : nearPositions(left.positions(), right.positions(), distance);
                    advanceLeft = true;
                    advanceRight = true;
                    if (matches.length > 0) {
                        setCurrent(leftDocId, matches.length, matches);
                        return true;
                    }
                    if (!prepare()) {
                        return false;
                    }
                } else if (leftDocId < rightDocId) {
                    if (!left.advanceTo(rightDocId)) {
                        return false;
                    }
                } else if (!right.advanceTo(leftDocId)) {
                    return false;
                }
            }
        }

        private boolean prepare() throws IOException {
            if (advanceLeft) {
                if (!left.next()) {
                    return false;
                }
                advanceLeft = false;
            } else if (!left.isPositioned() && !left.next()) {
                return false;
            }
            if (advanceRight) {
                if (!right.next()) {
                    return false;
                }
                advanceRight = false;
            } else if (!right.isPositioned() && !right.next()) {
                return false;
            }
            return true;
        }

        @Override
        public void close() throws IOException {
            closeBoth(left, right);
        }
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

    private static void closeBoth(PostingCursor left, PostingCursor right) throws IOException {
        IOException failure = null;
        try {
            left.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            right.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
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
