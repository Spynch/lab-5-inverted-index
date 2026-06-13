package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.ranking.BM25Scorer;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BM25Test {
    @Test
    void givesHigherScoreToDocumentWithHigherTermFrequency() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(Arrays.asList(
                new Document(1, "doc1", "information information information information retrieval"),
                new Document(2, "doc2", "information neural search graph retrieval")
        ));

        BM25Scorer scorer = new BM25Scorer(index);

        assertTrue(scorer.score(1, Collections.singleton("information")) > scorer.score(2, Collections.singleton("information")));
    }
}
