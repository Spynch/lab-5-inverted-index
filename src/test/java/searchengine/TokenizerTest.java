package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.tokenizer.SimpleTokenizer;
import searchengine.tokenizer.Token;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenizerTest {
    @Test
    void tokenizesLowercaseAndPositions() {
        List<Token> tokens = new SimpleTokenizer().tokenize("New York is big!");

        assertEquals("new:0,york:1,is:2,big:3", tokens.stream()
                .map(token -> token.getTerm() + ":" + token.getPosition())
                .collect(Collectors.joining(",")));
    }

    @Test
    void tokenizesUnicodeLettersAndDigitsWithoutEmptyTokens() {
        List<Token> tokens = new SimpleTokenizer().tokenize(
                "  \u041F\u0440\u0418\u0432\u0415\u0442\u2014\u041C\u0418\u0420 42... ");

        assertEquals(
                "\u043F\u0440\u0438\u0432\u0435\u0442:0,\u043C\u0438\u0440:1,42:2",
                tokens.stream()
                        .map(token -> token.getTerm() + ":" + token.getPosition())
                        .collect(Collectors.joining(",")));
    }
}
