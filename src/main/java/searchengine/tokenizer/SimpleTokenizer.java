package searchengine.tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class SimpleTokenizer implements Tokenizer {
    @Override
    public List<Token> tokenize(String text) {
        Objects.requireNonNull(text, "text");
        String normalized = text.toLowerCase(Locale.ROOT);
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        int tokenStart = -1;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (tokenStart < 0) {
                    tokenStart = offset;
                }
            } else if (tokenStart >= 0) {
                tokens.add(new Token(normalized.substring(tokenStart, offset), position++));
                tokenStart = -1;
            }
            offset = nextOffset;
        }
        if (tokenStart >= 0) {
            tokens.add(new Token(normalized.substring(tokenStart), position));
        }
        return tokens;
    }
}
