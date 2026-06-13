package searchengine.query;

public final class OrNode extends BinaryNode {
    public OrNode(QueryNode left, QueryNode right) {
        super(left, right);
    }
}
