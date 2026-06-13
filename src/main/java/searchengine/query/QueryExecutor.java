package searchengine.query;

import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.PostingList;
import searchengine.index.PostingListOperations;

import java.util.Objects;

public final class QueryExecutor {
    private final InMemoryInvertedIndex index;
    private final boolean useSkips;

    public QueryExecutor(InMemoryInvertedIndex index) {
        this(index, true);
    }

    public QueryExecutor(InMemoryInvertedIndex index, boolean useSkips) {
        this.index = Objects.requireNonNull(index, "index");
        this.useSkips = useSkips;
    }

    public PostingList execute(QueryNode node) {
        return execute(node, false);
    }

    private PostingList execute(QueryNode node, boolean positionsRequired) {
        Objects.requireNonNull(node, "node");
        if (node instanceof TermNode) {
            return index.getPostingList(((TermNode) node).getTerm());
        }
        if (node instanceof AndNode) {
            AndNode and = (AndNode) node;
            if (and.getRight() instanceof NotNode && !(and.getLeft() instanceof NotNode)) {
                PostingList left = execute(and.getLeft(), positionsRequired);
                PostingList excluded = execute(((NotNode) and.getRight()).getChild(), false);
                return PostingListOperations.andNot(left, excluded);
            }
            if (and.getLeft() instanceof NotNode && !(and.getRight() instanceof NotNode)) {
                PostingList right = execute(and.getRight(), positionsRequired);
                PostingList excluded = execute(((NotNode) and.getLeft()).getChild(), false);
                return PostingListOperations.andNot(right, excluded);
            }
            PostingList left = execute(and.getLeft(), positionsRequired);
            PostingList right = execute(and.getRight(), positionsRequired);
            return useSkips
                    ? PostingListOperations.andWithSkips(left, right, positionsRequired)
                    : PostingListOperations.andWithoutSkips(left, right, positionsRequired);
        }
        if (node instanceof OrNode) {
            OrNode or = (OrNode) node;
            return PostingListOperations.or(execute(or.getLeft(), positionsRequired),
                    execute(or.getRight(), positionsRequired), positionsRequired);
        }
        if (node instanceof NotNode) {
            NotNode not = (NotNode) node;
            return PostingListOperations.not(PostingList.empty("*"), execute(not.getChild(), false),
                    index.getUniverse());
        }
        if (node instanceof AdjNode) {
            AdjNode adj = (AdjNode) node;
            return PostingListOperations.adjacent(execute(adj.getLeft(), true), execute(adj.getRight(), true));
        }
        if (node instanceof NearNode) {
            NearNode near = (NearNode) node;
            return PostingListOperations.near(execute(near.getLeft(), true), execute(near.getRight(), true),
                    near.getDistance());
        }
        throw new IllegalArgumentException("Unsupported query node: " + node.getClass().getName());
    }
}
