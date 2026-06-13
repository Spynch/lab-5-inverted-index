package searchengine.ranking;

import searchengine.document.DocumentMeta;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.Posting;
import searchengine.index.PostingList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BM25Scorer {
    private static final double DEFAULT_K1 = 1.2;
    private static final double DEFAULT_B = 0.75;

    private final InMemoryInvertedIndex index;
    private final double k1;
    private final double b;

    public BM25Scorer(InMemoryInvertedIndex index) {
        this(index, DEFAULT_K1, DEFAULT_B);
    }

    public BM25Scorer(InMemoryInvertedIndex index, double k1, double b) {
        this.index = Objects.requireNonNull(index, "index");
        this.k1 = k1;
        this.b = b;
    }

    public List<SearchResult> rank(PostingList candidates, Collection<String> queryTerms, int topK) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(queryTerms, "queryTerms");
        if (topK <= 0 || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Integer, Double> scores = new HashMap<>();
        for (Posting candidate : candidates.getPostings()) {
            scores.put(candidate.getDocId(), 0.0);
        }
        for (String term : queryTerms) {
            PostingList postingList = index.getPostingList(term);
            double idf = idf(postingList.size());
            for (Posting posting : postingList.getPostings()) {
                if (scores.containsKey(posting.getDocId())) {
                    scores.put(posting.getDocId(), scores.get(posting.getDocId()) + scoreTerm(idf, posting));
                }
            }
        }
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            DocumentMeta meta = index.getDocumentMeta(entry.getKey());
            if (meta != null) {
                results.add(new SearchResult(meta.getDocId(), meta.getExternalId(), entry.getValue()));
            }
        }
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed()
                .thenComparingInt(SearchResult::getDocId));
        if (results.size() > topK) {
            return new ArrayList<>(results.subList(0, topK));
        }
        return results;
    }

    public double score(int docId, Collection<String> queryTerms) {
        double score = 0.0;
        for (String term : queryTerms) {
            PostingList postingList = index.getPostingList(term);
            double idf = idf(postingList.size());
            for (Posting posting : postingList.getPostings()) {
                if (posting.getDocId() == docId) {
                    score += scoreTerm(idf, posting);
                    break;
                }
            }
        }
        return score;
    }

    private double scoreTerm(double idf, Posting posting) {
        DocumentMeta meta = index.getDocumentMeta(posting.getDocId());
        if (meta == null || index.getAvgDocumentLength() == 0.0) {
            return 0.0;
        }
        double tf = posting.getTermFrequency();
        double normalization = tf + k1 * (1.0 - b + b * meta.getLength() / index.getAvgDocumentLength());
        return idf * tf * (k1 + 1.0) / normalization;
    }

    private double idf(int documentFrequency) {
        int totalDocuments = index.getDocumentCount();
        if (documentFrequency <= 0 || totalDocuments <= 0) {
            return 0.0;
        }
        return Math.log(1.0 + (totalDocuments - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }
}
