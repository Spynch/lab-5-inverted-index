package searchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.query.AntlrQueryParser;
import searchengine.query.QueryExecutor;
import searchengine.query.QueryNode;
import searchengine.ranking.SearchResult;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.DiskSearchEngine;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryCombinationTest {
    @TempDir
    Path tempDir;

    @Test
    void supportsRequiredBooleanAndPositionalCombinationsInMemory() {
        InMemoryInvertedIndex index = index();

        assertQuery(index, "history AND russia", 1, 2, 4);
        assertQuery(index, "history OR russia", 1, 2, 3, 4);
        assertQuery(index, "history EDGE russia", 1);
        assertQuery(index, "history AND NOT russia", 3);
        assertQuery(index, "NOT (history ADJ russia)", 2, 3, 4, 5);
        assertQuery(index, "history AND NOT (history ADJ russia)", 2, 3, 4);
        assertQuery(index, "history AND NOT (russia NEAR/1 history)", 2, 3);
        assertQuery(index, "blue AND mountain", 1, 2, 3, 4);
        assertQuery(index, "blue AND NOT (blue ADJ mountain)", 2, 4);
        assertQuery(index, "(history OR blue) ADJ mountain", 1, 3, 4);
        assertQuery(index, "(history AND blue) ADJ mountain", 1, 3, 4);
        assertQuery(index, "history ADJ (russia ADJ blue)", 1);
    }

    @Test
    void positionalResultsContainValidatedAnchorPositions() {
        InMemoryInvertedIndex index = index();
        PostingList adjacent = execute(index, "history ADJ russia");
        PostingList near = execute(index, "history NEAR/1 russia");

        assertEquals(Collections.singletonList(1), docIds(adjacent));
        assertArrayEquals(new int[] {1}, adjacent.get(0).getPositions());
        assertEquals(Arrays.asList(1, 4), docIds(near));
        assertArrayEquals(new int[] {0}, near.get(0).getPositions());
        assertArrayEquals(new int[] {1}, near.get(1).getPositions());
    }

    @Test
    void diskExecutionMatchesInMemoryAndProvidesDiagnostics() throws Exception {
        InMemoryInvertedIndex index = index();
        new DiskIndexWriter().write(index, tempDir);

        try (DiskSearchEngine engine = DiskSearchEngine.mmap(tempDir)) {
            assertEquals(Arrays.asList(2, 4), docIds(engine.execute("blue AND NOT (blue ADJ mountain)")));
            assertEquals(Arrays.asList(1, 3, 4), docIds(engine.execute("(history OR blue) ADJ mountain")));
            assertEquals(Collections.singletonList(1), docIds(engine.execute("history ADJ (russia ADJ blue)")));
            List<SearchResult> results = engine.searchDetailed("history AND NOT (history ADJ russia)",
                    10, false);
            assertEquals(Arrays.asList(2, 3, 4), resultDocIds(results));
            assertTrue(results.get(0).getSnippet().contains("history"));
            assertArrayEquals(new int[] {0}, results.get(0).getTermPositions().get("history"));
            assertArrayEquals(new int[] {2}, results.get(0).getTermPositions().get("russia"));
        }
    }

    @Test
    void negatedTermsDoNotContributeToRanking() {
        InMemoryInvertedIndex index = index();
        QueryNode node = new AntlrQueryParser().parse("history AND NOT (blue ADJ mountain)");

        assertEquals(Collections.singleton("history"), node.positiveTerms());
        assertEquals(Arrays.asList("history", "blue", "mountain"), node.terms().stream().collect(Collectors.toList()));
    }

    @Test
    void parserReportsInvalidQueries() {
        AntlrQueryParser parser = new AntlrQueryParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse("history AND (russia OR)"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("history NEAR/x russia"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
    }

    private static InMemoryInvertedIndex index() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(Arrays.asList(
                new Document(1, "doc1", "history russia blue mountain"),
                new Document(2, "doc2", "history of russia blue high mountain"),
                new Document(3, "doc3", "history europe blue mountain"),
                new Document(4, "doc4", "russia history mountain blue"),
                new Document(5, "doc5", "other document")
        ));
        return index;
    }

    private static void assertQuery(InMemoryInvertedIndex index, String query, Integer... expectedDocIds) {
        assertEquals(Arrays.asList(expectedDocIds), docIds(execute(index, query)), query);
    }

    private static PostingList execute(InMemoryInvertedIndex index, String query) {
        return new QueryExecutor(index).execute(new AntlrQueryParser().parse(query));
    }

    private static List<Integer> docIds(PostingList postingList) {
        return postingList.getPostings().stream().map(Posting::getDocId).collect(Collectors.toList());
    }

    private static List<Integer> resultDocIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocId).collect(Collectors.toList());
    }
}
