package searchengine.query;

public final class AndNode extends BinaryNode {
    public AndNode(QueryNode left, QueryNode right) {
        super(left, right);
    }
}
