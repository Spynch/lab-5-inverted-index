package searchengine.query;

import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class TermNode implements QueryNode {
    private final String term;

    public TermNode(String term) {
        this.term = Objects.requireNonNull(term, "term").toLowerCase(Locale.ROOT);
    }

    public String getTerm() {
        return term;
    }

    @Override
    public Set<String> terms() {
        return Collections.singleton(term);
    }
}
