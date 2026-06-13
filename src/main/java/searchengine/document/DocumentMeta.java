package searchengine.document;

import java.util.Objects;

public final class DocumentMeta {
    private final int docId;
    private final String externalId;
    private final int length;
    private final String snippet;

    public DocumentMeta(int docId, String externalId, int length) {
        this(docId, externalId, length, "");
    }

    public DocumentMeta(int docId, String externalId, int length, String snippet) {
        this.docId = docId;
        this.externalId = Objects.requireNonNull(externalId, "externalId");
        this.length = length;
        this.snippet = Objects.requireNonNull(snippet, "snippet");
    }

    public int getDocId() {
        return docId;
    }

    public String getExternalId() {
        return externalId;
    }

    public int getLength() {
        return length;
    }

    public String getSnippet() {
        return snippet;
    }
}
