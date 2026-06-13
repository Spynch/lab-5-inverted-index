package searchengine.query;

import java.util.Set;

public interface QueryNode {
    Set<String> terms();

    default Set<String> positiveTerms() {
        return terms();
    }
}
