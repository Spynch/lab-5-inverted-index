package searchengine.index;

import searchengine.document.Document;
import searchengine.document.DocumentMeta;
import searchengine.query.AntlrQueryParser;
import searchengine.query.QueryExecutor;
import searchengine.query.QueryNode;
import searchengine.query.QueryParser;
import searchengine.ranking.BM25Scorer;
import searchengine.ranking.SearchResult;
import searchengine.tokenizer.SimpleTokenizer;
import searchengine.tokenizer.Token;
import searchengine.tokenizer.Tokenizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class InMemoryInvertedIndex {
    private static final int MAX_SNIPPET_LENGTH = 500;

    private final Tokenizer tokenizer;
    private final Map<String, PostingList> index = new HashMap<>();
    private final Map<Integer, DocumentMeta> documents = new LinkedHashMap<>();
    private double avgDocumentLength;

    public InMemoryInvertedIndex() {
        this(new SimpleTokenizer());
    }

    public InMemoryInvertedIndex(Tokenizer tokenizer) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
    }

    public void build(Iterable<Document> documents) {
        Objects.requireNonNull(documents, "documents");
        build(documents.iterator());
    }

    public void build(Iterator<Document> iterator) {
        Objects.requireNonNull(iterator, "iterator");
        index.clear();
        documents.clear();
        Map<String, Map<Integer, IntPositions>> termPositions = new HashMap<>();
        long totalLength = 0L;
        int generatedDocId = 1;
        while (iterator.hasNext()) {
            Document document = iterator.next();
            int docId = document.getDocId() > 0 ? document.getDocId() : generatedDocId;
            generatedDocId = Math.max(generatedDocId, docId + 1);
            if (documents.containsKey(docId)) {
                throw new IllegalArgumentException("Duplicate docId: " + docId);
            }
            List<Token> tokens = tokenizer.tokenize(document.getText());
            documents.put(docId, new DocumentMeta(docId, document.getExternalId(), tokens.size(),
                    snippet(document.getText())));
            totalLength += tokens.size();
            for (Token token : tokens) {
                termPositions
                        .computeIfAbsent(token.getTerm(), ignored -> new HashMap<>())
                        .computeIfAbsent(docId, ignored -> new IntPositions())
                        .add(token.getPosition());
            }
        }
        for (Map.Entry<String, Map<Integer, IntPositions>> termEntry : termPositions.entrySet()) {
            List<Posting> postings = new ArrayList<>();
            for (Map.Entry<Integer, IntPositions> docEntry : termEntry.getValue().entrySet()) {
                int[] positions = docEntry.getValue().toArray();
                postings.add(new Posting(docEntry.getKey(), positions.length, positions));
            }
            index.put(termEntry.getKey(), new PostingList(termEntry.getKey(), postings));
        }
        avgDocumentLength = documents.isEmpty() ? 0.0 : (double) totalLength / documents.size();
    }

    public PostingList getPostingList(String term) {
        if (term == null) {
            return PostingList.empty("");
        }
        String normalized = term.toLowerCase(Locale.ROOT);
        return index.getOrDefault(normalized, PostingList.empty(normalized));
    }

    public Map<String, PostingList> getIndex() {
        return Collections.unmodifiableMap(index);
    }

    public Map<Integer, DocumentMeta> getDocuments() {
        return Collections.unmodifiableMap(documents);
    }

    public DocumentMeta getDocumentMeta(int docId) {
        return documents.get(docId);
    }

    public int getDocumentCount() {
        return documents.size();
    }

    public int getDocumentFrequency(String term) {
        return getPostingList(term).size();
    }

    public double getAvgDocumentLength() {
        return avgDocumentLength;
    }

    public DocIdSet getUniverse() {
        return new DocIdSet(new ArrayList<>(documents.keySet()));
    }

    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, true);
    }

    public List<SearchResult> search(String query, int topK, boolean rankResults) {
        QueryParser parser = new AntlrQueryParser();
        QueryNode node = parser.parse(query);
        PostingList candidates = new QueryExecutor(this).execute(node);
        if (rankResults) {
            return new BM25Scorer(this).rank(candidates, node.positiveTerms(), topK);
        }
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < candidates.size() && results.size() < topK; i++) {
            DocumentMeta meta = documents.get(candidates.get(i).getDocId());
            if (meta != null) {
                results.add(new SearchResult(meta.getDocId(), meta.getExternalId(), 0.0));
            }
        }
        return results;
    }

    private static String snippet(String text) {
        StringBuilder result = new StringBuilder(Math.min(text.length(), MAX_SNIPPET_LENGTH + 3));
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isWhitespace(value)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace && result.length() < MAX_SNIPPET_LENGTH) {
                result.append(' ');
            }
            pendingSpace = false;
            if (result.length() >= MAX_SNIPPET_LENGTH) {
                return result.append("...").toString();
            }
            result.append(value);
        }
        return result.toString();
    }

    private static final class IntPositions {
        private int[] values = new int[4];
        private int size;

        void add(int value) {
            if (size == values.length) {
                int[] next = new int[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        int[] toArray() {
            int[] result = new int[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
