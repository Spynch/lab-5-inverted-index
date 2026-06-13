package searchengine.storage;

import searchengine.document.DocumentMeta;
import searchengine.index.DocIdSet;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.query.AntlrQueryParser;
import searchengine.query.QueryNode;
import searchengine.query.QueryParser;
import searchengine.query.StreamingQueryExecutor;
import searchengine.ranking.DiskBM25Scorer;
import searchengine.ranking.SearchPage;
import searchengine.ranking.SearchResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DiskSearchEngine implements AutoCloseable {
    private final AbstractDiskIndexReader reader;
    private final QueryParser parser;
    private final DocIdSet universe;
    private final DiskBM25Scorer scorer;

    public DiskSearchEngine(DiskIndexReader reader) {
        this((AbstractDiskIndexReader) reader);
    }

    public DiskSearchEngine(MMapIndexReader reader) {
        this((AbstractDiskIndexReader) reader);
    }

    public static DiskSearchEngine disk(Path directory) throws IOException {
        return new DiskSearchEngine(new DiskIndexReader(directory));
    }

    public static DiskSearchEngine mmap(Path directory) throws IOException {
        return new DiskSearchEngine(new MMapIndexReader(directory));
    }

    private DiskSearchEngine(AbstractDiskIndexReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.parser = new AntlrQueryParser();
        Map<Integer, DocumentMeta> documents = reader.getDocuments();
        this.universe = new DocIdSet(new ArrayList<>(documents.keySet()));
        this.scorer = new DiskBM25Scorer(reader, documents, avgDocumentLength(documents));
    }

    public PostingList execute(String query) throws IOException {
        List<Posting> postings = new ArrayList<>();
        try (PostingCursor cursor = openCursor(query)) {
            while (cursor.next()) {
                postings.add(new Posting(cursor.docId(), cursor.termFrequency(), cursor.positions()));
            }
        }
        return new PostingList(query, postings);
    }

    public PostingCursor openCursor(String query) throws IOException {
        QueryNode node = parser.parse(query);
        return new StreamingQueryExecutor(reader, universe).execute(node);
    }

    public List<SearchResult> search(String query, int topK) throws IOException {
        return search(query, topK, true, false);
    }

    public List<SearchResult> search(String query, int topK, boolean rankResults) throws IOException {
        return search(query, topK, rankResults, false);
    }

    public List<SearchResult> searchDetailed(String query, int topK, boolean rankResults) throws IOException {
        return search(query, topK, rankResults, true);
    }

    public SearchPage searchPage(String query, int offset, int limit,
                                 boolean rankResults, boolean includeDiagnostics) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        QueryNode node = parser.parse(query);
        SearchPage page;
        try (PostingCursor candidates = new StreamingQueryExecutor(reader, universe).execute(node)) {
            page = rankResults
                    ? scorer.rankPage(candidates, node.positiveTerms(), offset, limit)
                    : collectUnrankedPage(candidates, offset, limit);
        }
        if (!includeDiagnostics || page.getResults().isEmpty()) {
            return page;
        }
        List<SearchResult> detailed = addDiagnostics(page.getResults(), node);
        return new SearchPage(page.getTotalMatches(), offset, limit, detailed);
    }

    public DocumentMeta getDocumentMeta(int docId) {
        return reader.getDocumentMeta(docId);
    }

    public int[] termPositions(String term, int docId) throws IOException {
        try (PostingCursor cursor = reader.openCursor(term, true)) {
            if (cursor.advanceTo(docId) && cursor.docId() == docId) {
                return cursor.positions().clone();
            }
        }
        return new int[0];
    }

    private List<SearchResult> search(String query, int topK, boolean rankResults, boolean includeDiagnostics)
            throws IOException {
        QueryNode node = parser.parse(query);
        List<SearchResult> results;
        try (PostingCursor candidates = new StreamingQueryExecutor(reader, universe).execute(node)) {
            results = rankResults
                    ? scorer.rank(candidates, node.positiveTerms(), topK)
                    : collectUnranked(candidates, topK);
        }
        if (!includeDiagnostics) {
            return results;
        }
        return addDiagnostics(results, node);
    }

    private List<SearchResult> addDiagnostics(List<SearchResult> results, QueryNode node) throws IOException {
        List<SearchResult> detailed = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            DocumentMeta meta = reader.getDocumentMeta(result.getDocId());
            detailed.add(new SearchResult(result.getDocId(), result.getExternalId(), result.getScore(),
                    meta == null ? "" : meta.getSnippet(), positionsFor(node.terms(), result.getDocId())));
        }
        return detailed;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private static double avgDocumentLength(Map<Integer, DocumentMeta> documents) {
        if (documents.isEmpty()) {
            return 0.0;
        }
        long totalLength = 0L;
        for (DocumentMeta meta : documents.values()) {
            totalLength += meta.getLength();
        }
        return (double) totalLength / documents.size();
    }

    private List<SearchResult> collectUnranked(PostingCursor candidates, int topK) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        while (results.size() < topK && candidates.next()) {
            DocumentMeta meta = reader.getDocumentMeta(candidates.docId());
            if (meta != null) {
                results.add(new SearchResult(meta.getDocId(), meta.getExternalId(), 0.0));
            }
        }
        return results;
    }

    private SearchPage collectUnrankedPage(PostingCursor candidates, int offset, int limit) throws IOException {
        List<SearchResult> results = new ArrayList<>(limit);
        int totalMatches = 0;
        while (candidates.next()) {
            DocumentMeta meta = reader.getDocumentMeta(candidates.docId());
            if (meta == null) {
                continue;
            }
            if (totalMatches >= offset && results.size() < limit) {
                results.add(new SearchResult(meta.getDocId(), meta.getExternalId(), 0.0));
            }
            totalMatches++;
        }
        return new SearchPage(totalMatches, offset, limit, results);
    }

    private Map<String, int[]> positionsFor(Iterable<String> terms, int docId) throws IOException {
        Map<String, int[]> positions = new LinkedHashMap<>();
        for (String term : terms) {
            int[] termPositions = termPositions(term, docId);
            if (termPositions.length > 0) {
                positions.put(term, termPositions);
            }
        }
        return positions;
    }
}
