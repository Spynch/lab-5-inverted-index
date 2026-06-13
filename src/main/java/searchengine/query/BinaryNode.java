package searchengine.query;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

abstract class BinaryNode implements QueryNode {
    private final QueryNode left;
    private final QueryNode right;

    BinaryNode(QueryNode left, QueryNode right) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
    }

    public QueryNode getLeft() {
        return left;
    }

    public QueryNode getRight() {
        return right;
    }

    @Override
    public Set<String> terms() {
        Set<String> terms = new LinkedHashSet<>(left.terms());
        terms.addAll(right.terms());
        return terms;
    }

    @Override
    public Set<String> positiveTerms() {
        Set<String> terms = new LinkedHashSet<>(left.positiveTerms());
        terms.addAll(right.positiveTerms());
        return terms;
    }
}
