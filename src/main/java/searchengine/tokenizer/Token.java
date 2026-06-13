package searchengine.tokenizer;

import java.util.Objects;

public final class Token {
    private final String term;
    private final int position;

    public Token(String term, int position) {
        this.term = Objects.requireNonNull(term, "term");
        this.position = position;
    }

    public String getTerm() {
        return term;
    }

    public int getPosition() {
        return position;
    }
}
