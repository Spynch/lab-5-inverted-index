package searchengine.document;

import java.util.Objects;

public final class Document {
    private final int docId;
    private final String externalId;
    private final String text;

    public Document(int docId, String externalId, String text) {
        this.docId = docId;
        this.externalId = Objects.requireNonNull(externalId, "externalId");
        this.text = Objects.requireNonNull(text, "text");
    }

    public int getDocId() {
        return docId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getText() {
        return text;
    }
}
