package searchengine.query;

public final class NearNode extends BinaryNode {
    private final int distance;

    public NearNode(QueryNode left, QueryNode right, int distance) {
        super(left, right);
        if (distance < 0) {
            throw new IllegalArgumentException("distance must be non-negative");
        }
        this.distance = distance;
    }

    public int getDistance() {
        return distance;
    }
}
