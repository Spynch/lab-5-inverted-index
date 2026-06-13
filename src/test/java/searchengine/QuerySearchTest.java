package searchengine;

import org.junit.jupiter.api.Test;
import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.Posting;
import searchengine.query.AntlrQueryParser;
import searchengine.query.AdjNode;
import searchengine.query.AndNode;
import searchengine.query.QueryNode;
import searchengine.ranking.SearchResult;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class QuerySearchTest {
    @Test
    void parsesQueryIntoAst() {
        QueryNode node = new AntlrQueryParser().parse("(apple OR banana) AND NOT juice");
        assertInstanceOf(AndNode.class, node);
    }

    @Test
    void searchesAdjacentAndNearQueries() {
        InMemoryInvertedIndex index = index();

        assertEquals(Arrays.asList(1), docIds(index.search("new ADJ york", 10)));
        assertEquals(Arrays.asList(1, 2), sortedDocIds(index.search("new NEAR/2 york", 10)));
    }

    @Test
    void searchesBooleanQueryWithNot() {
        InMemoryInvertedIndex index = index();

        assertEquals(Arrays.asList(2), docIds(index.search("(apple OR banana) AND NOT juice", 10)));
    }

    private static InMemoryInvertedIndex index() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(Arrays.asList(
                new Document(1, "doc1", "new york city apple juice"),
                new Document(2, "doc2", "new big york banana"),
                new Document(3, "doc3", "apple banana juice")
        ));
        return index;
    }

    private static List<Integer> docIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocId).collect(Collectors.toList());
    }

    private static List<Integer> sortedDocIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocId).sorted().collect(Collectors.toList());
    }
}
