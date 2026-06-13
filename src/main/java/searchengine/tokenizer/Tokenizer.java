package searchengine.tokenizer;

import java.util.List;

public interface Tokenizer {
    List<Token> tokenize(String text);
}
