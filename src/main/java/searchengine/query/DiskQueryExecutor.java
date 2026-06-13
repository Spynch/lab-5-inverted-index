package searchengine.query;

import searchengine.index.DocIdSet;
import searchengine.index.PostingList;
import searchengine.index.PostingListOperations;
import searchengine.storage.PostingListReader;

import java.io.IOException;
import java.util.Objects;

public final class DiskQueryExecutor {
    private final PostingListReader reader;
    private final DocIdSet universe;
    private final boolean useSkips;

    public DiskQueryExecutor(PostingListReader reader, DocIdSet universe) {
        this(reader, universe, true);
    }

    public DiskQueryExecutor(PostingListReader reader, DocIdSet universe, boolean useSkips) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.universe = Objects.requireNonNull(universe, "universe");
        this.useSkips = useSkips;
    }

    public PostingList execute(QueryNode node) throws IOException {
        return execute(node, false);
    }

    private PostingList execute(QueryNode node, boolean positionsRequired) throws IOException {
        Objects.requireNonNull(node, "node");
        if (node instanceof TermNode) {
            String term = ((TermNode) node).getTerm();
            return positionsRequired ? reader.readPostingList(term) : reader.readDocIdPostingList(term);
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
            return PostingListOperations.not(PostingList.empty("*"), execute(not.getChild(), false), universe);
        }
        if (node instanceof AdjNode) {
            AdjNode adj = (AdjNode) node;
            return PostingListOperations.adjacent(execute(adj.getLeft(), true), execute(adj.getRight(), true));
        }
        if (node instanceof NearNode) {
            NearNode near = (NearNode) node;
            return PostingListOperations.near(execute(near.getLeft(), true), execute(near.getRight(), true), near.getDistance());
        }
        throw new IllegalArgumentException("Unsupported query node: " + node.getClass().getName());
    }
}
