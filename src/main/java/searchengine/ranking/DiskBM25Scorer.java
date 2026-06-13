package searchengine.ranking;

import searchengine.document.DocumentMeta;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.storage.PostingCursor;
import searchengine.storage.PostingListReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

public final class DiskBM25Scorer {
    private static final double DEFAULT_K1 = 1.2;
    private static final double DEFAULT_B = 0.75;

    private final PostingListReader reader;
    private final Map<Integer, DocumentMeta> documents;
    private final int documentCount;
    private final double avgDocumentLength;
    private final double k1;
    private final double b;

    public DiskBM25Scorer(PostingListReader reader, Map<Integer, DocumentMeta> documents, double avgDocumentLength) {
        this(reader, documents, avgDocumentLength, DEFAULT_K1, DEFAULT_B);
    }

    public DiskBM25Scorer(PostingListReader reader, Map<Integer, DocumentMeta> documents,
                          double avgDocumentLength, double k1, double b) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.documentCount = documents.size();
        this.avgDocumentLength = avgDocumentLength;
        this.k1 = k1;
        this.b = b;
    }

    public List<SearchResult> rank(PostingList candidates, Collection<String> queryTerms, int topK) throws IOException {
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
            PostingList postingList = reader.readDocIdPostingList(term);
            double idf = idf(postingList.size());
            for (Posting posting : postingList.getPostings()) {
                if (scores.containsKey(posting.getDocId())) {
                    scores.put(posting.getDocId(), scores.get(posting.getDocId()) + scoreTerm(idf, posting));
                }
            }
        }
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : scores.entrySet()) {
            DocumentMeta meta = documents.get(entry.getKey());
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

    public List<SearchResult> rank(PostingCursor candidates, Collection<String> queryTerms, int topK) throws IOException {
        if (topK <= 0) {
            return new ArrayList<>();
        }
        return rankPage(candidates, queryTerms, 0, topK).getResults();
    }

    public SearchPage rankPage(PostingCursor candidates, Collection<String> queryTerms,
                               int offset, int limit) throws IOException {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(queryTerms, "queryTerms");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int topK = Math.addExact(offset, limit);

        List<TermState> terms = new ArrayList<>();
        try {
            for (String term : queryTerms) {
                int documentFrequency = reader.getDocumentFrequency(term);
                if (documentFrequency > 0) {
                    terms.add(new TermState(reader.openCursor(term, false), idf(documentFrequency)));
                }
            }

            Comparator<SearchResult> worstFirst = Comparator
                    .comparingDouble(SearchResult::getScore)
                    .thenComparing(Comparator.comparingInt(SearchResult::getDocId).reversed());
            PriorityQueue<SearchResult> top = new PriorityQueue<>(topK, worstFirst);
            int totalMatches = 0;

            while (candidates.next()) {
                int docId = candidates.docId();
                DocumentMeta meta = documents.get(docId);
                if (meta == null) {
                    continue;
                }
                totalMatches++;
                double score = scoreCandidate(docId, terms);
                SearchResult result = new SearchResult(meta.getDocId(), meta.getExternalId(), score);
                if (top.size() < topK) {
                    top.add(result);
                } else if (worstFirst.compare(result, top.peek()) > 0) {
                    top.poll();
                    top.add(result);
                }
            }

            List<SearchResult> results = new ArrayList<>(top);
            results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed()
                    .thenComparingInt(SearchResult::getDocId));
            int fromIndex = Math.min(offset, results.size());
            int toIndex = Math.min(fromIndex + limit, results.size());
            return new SearchPage(totalMatches, offset, limit, results.subList(fromIndex, toIndex));
        } finally {
            for (TermState term : terms) {
                term.close();
            }
        }
    }

    public double score(int docId, Collection<String> queryTerms) throws IOException {
        double score = 0.0;
        for (String term : queryTerms) {
            PostingList postingList = reader.readDocIdPostingList(term);
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
        DocumentMeta meta = documents.get(posting.getDocId());
        if (meta == null || avgDocumentLength == 0.0) {
            return 0.0;
        }
        double tf = posting.getTermFrequency();
        double normalization = tf + k1 * (1.0 - b + b * meta.getLength() / avgDocumentLength);
        return idf * tf * (k1 + 1.0) / normalization;
    }

    private double idf(int documentFrequency) {
        if (documentFrequency <= 0 || documentCount <= 0) {
            return 0.0;
        }
        return Math.log(1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }

    private double scoreCandidate(int docId, List<TermState> terms) throws IOException {
        double score = 0.0;
        for (TermState term : terms) {
            if (term.cursor.advanceTo(docId) && term.cursor.docId() == docId) {
                score += scoreTerm(term.idf, docId, term.cursor.termFrequency());
            }
        }
        return score;
    }

    private double scoreTerm(double idf, int docId, int termFrequency) {
        DocumentMeta meta = documents.get(docId);
        if (meta == null || avgDocumentLength == 0.0) {
            return 0.0;
        }
        double tf = termFrequency;
        double normalization = tf + k1 * (1.0 - b + b * meta.getLength() / avgDocumentLength);
        return idf * tf * (k1 + 1.0) / normalization;
    }

    private static final class TermState implements AutoCloseable {
        private final PostingCursor cursor;
        private final double idf;

        private TermState(PostingCursor cursor, double idf) {
            this.cursor = cursor;
            this.idf = idf;
        }

        @Override
        public void close() throws IOException {
            cursor.close();
        }
    }
}
