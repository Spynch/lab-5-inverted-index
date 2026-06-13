package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.Posting;
import searchengine.index.PostingList;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexBuilderTest {
    @Test
    void buildsCoordinateIndexWithTfAndDf() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(Arrays.asList(
                new Document(1, "doc1", "hello world hello"),
                new Document(5, "doc5", "world hello")
        ));

        PostingList hello = index.getPostingList("hello");
        assertEquals(2, hello.size());
        Posting first = hello.getPostings().get(0);
        assertEquals(1, first.getDocId());
        assertEquals(2, first.getTermFrequency());
        assertArrayEquals(new int[] {0, 2}, first.getPositions());
        assertEquals(2, index.getDocumentFrequency("hello"));
        assertEquals(3, index.getDocumentMeta(1).getLength());
    }
}
