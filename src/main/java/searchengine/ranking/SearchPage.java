package searchengine.ranking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SearchPage {
    private final int totalMatches;
    private final int offset;
    private final int limit;
    private final List<SearchResult> results;

    public SearchPage(int totalMatches, int offset, int limit, List<SearchResult> results) {
        if (totalMatches < 0) {
            throw new IllegalArgumentException("totalMatches must be non-negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.totalMatches = totalMatches;
        this.offset = offset;
        this.limit = limit;
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
    }

    public int getTotalMatches() {
        return totalMatches;
    }

    public int getOffset() {
        return offset;
    }

    public int getLimit() {
        return limit;
    }

    public List<SearchResult> getResults() {
        return results;
    }

    public boolean hasMore() {
        return (long) offset + results.size() < totalMatches;
    }
}
