package searchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.ranking.SearchPage;
import searchengine.ranking.SearchResult;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.DiskSearchEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchPaginationTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsExactTotalAndStableRankedPages() throws Exception {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        List<Document> documents = new ArrayList<>();
        for (int docId = 1; docId <= 23; docId++) {
            documents.add(new Document(docId, String.valueOf(1000 + docId),
                    "common ".repeat((docId % 5) + 1) + "term" + docId));
        }
        index.build(documents);
        new DiskIndexWriter().write(index, tempDir);

        try (DiskSearchEngine engine = DiskSearchEngine.mmap(tempDir)) {
            SearchPage first = engine.searchPage("common", 0, 10, true, true);
            SearchPage second = engine.searchPage("common", 10, 10, true, true);
            SearchPage third = engine.searchPage("common", 20, 10, true, true);
            List<Integer> paged = new ArrayList<>();
            paged.addAll(docIds(first.getResults()));
            paged.addAll(docIds(second.getResults()));
            paged.addAll(docIds(third.getResults()));

            assertEquals(23, first.getTotalMatches());
            assertEquals(10, first.getResults().size());
            assertTrue(first.hasMore());
            assertEquals(10, second.getResults().size());
            assertTrue(second.hasMore());
            assertEquals(3, third.getResults().size());
            assertFalse(third.hasMore());
            assertEquals(docIds(engine.search("common", 23, true)), paged);
            assertFalse(first.getResults().get(0).getSnippet().isEmpty());
        }
    }

    private static List<Integer> docIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocId).collect(Collectors.toList());
    }
}
