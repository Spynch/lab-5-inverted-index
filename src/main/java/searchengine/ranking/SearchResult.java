package searchengine.ranking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SearchResult {
    private final int docId;
    private final String externalId;
    private final double score;
    private final String snippet;
    private final Map<String, int[]> termPositions;

    public SearchResult(int docId, String externalId, double score) {
        this(docId, externalId, score, "", Collections.emptyMap());
    }

    public SearchResult(int docId, String externalId, double score, String snippet,
                        Map<String, int[]> termPositions) {
        this.docId = docId;
        this.externalId = externalId;
        this.score = score;
        this.snippet = snippet == null ? "" : snippet;
        Map<String, int[]> copiedPositions = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : termPositions.entrySet()) {
            copiedPositions.put(entry.getKey(), entry.getValue().clone());
        }
        this.termPositions = Collections.unmodifiableMap(copiedPositions);
    }

    public int getDocId() {
        return docId;
    }

    public String getExternalId() {
        return externalId;
    }

    public double getScore() {
        return score;
    }

    public String getSnippet() {
        return snippet;
    }

    public Map<String, int[]> getTermPositions() {
        Map<String, int[]> copiedPositions = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : termPositions.entrySet()) {
            copiedPositions.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copiedPositions);
    }
}
