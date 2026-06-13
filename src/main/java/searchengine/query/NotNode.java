package searchengine.query;

import java.util.Objects;
import java.util.Collections;
import java.util.Set;

public final class NotNode implements QueryNode {
    private final QueryNode child;

    public NotNode(QueryNode child) {
        this.child = Objects.requireNonNull(child, "child");
    }

    public QueryNode getChild() {
        return child;
    }

    @Override
    public Set<String> terms() {
        return child.terms();
    }

    @Override
    public Set<String> positiveTerms() {
        return Collections.emptySet();
    }
}
