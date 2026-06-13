package searchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import searchengine.document.Document;
import searchengine.compression.IndexCompressionMode;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.ranking.SearchResult;
import searchengine.storage.DiskIndexReader;
import searchengine.storage.DiskIndexStats;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.DiskSearchEngine;
import searchengine.storage.MMapIndexReader;
import searchengine.storage.PostingCursor;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void writesExpectedIndexFiles() throws Exception {
        InMemoryInvertedIndex index = index();
        DiskIndexStats stats = new DiskIndexWriter().write(index, tempDir);

        assertTrue(Files.exists(tempDir.resolve("dictionary.bin")));
        assertTrue(Files.exists(tempDir.resolve("postings.bin")));
        assertTrue(Files.exists(tempDir.resolve("positions.bin")));
        assertTrue(Files.exists(tempDir.resolve("documents.bin")));
        assertTrue(Files.exists(tempDir.resolve("meta.json")));
        assertTrue(stats.getTotalBytes() > 0);
    }

    @Test
    void writesCompressedDictionaryAndDocumentsInCurrentFormat() throws Exception {
        InMemoryInvertedIndex index = index();
        new DiskIndexWriter().write(index, tempDir);

        try (DataInputStream dictionaryIn = new DataInputStream(Files.newInputStream(tempDir.resolve("dictionary.bin")));
             DataInputStream documentsIn = new DataInputStream(Files.newInputStream(tempDir.resolve("documents.bin")))) {
            assertEquals(4, dictionaryIn.readInt());
            assertEquals(IndexCompressionMode.DELTA_VARBYTE.ordinal(), dictionaryIn.readInt());
            assertEquals(4, documentsIn.readInt());
        }
        String meta = Files.readString(tempDir.resolve("meta.json"));
        assertTrue(meta.contains("\"dictionaryCompression\": \"deflate\""));
        assertTrue(meta.contains("\"documentCompression\": \"deflate\""));
        assertTrue(meta.contains("\"docIdCompression\": \"delta-varbyte\""));
    }

    @Test
    void diskReaderRestoresPostingList() throws Exception {
        InMemoryInvertedIndex index = index();
        new DiskIndexWriter().write(index, tempDir);

        try (DiskIndexReader reader = new DiskIndexReader(tempDir)) {
            assertPostingList(reader.readPostingList("hello"));
            assertEquals("doc1", reader.getDocumentMeta(1).getExternalId());
            assertEquals("hello world hello", reader.getDocumentMeta(1).getSnippet());
        }
    }

    @Test
    void allCompressionModesProduceReadableIndexes() throws Exception {
        InMemoryInvertedIndex index = index();
        for (IndexCompressionMode mode : IndexCompressionMode.values()) {
            Path modeDirectory = tempDir.resolve(mode.getId());
            new DiskIndexWriter(mode).write(index, modeDirectory);
            try (MMapIndexReader reader = new MMapIndexReader(modeDirectory)) {
                assertPostingList(reader.readPostingList("hello"));
            }
        }
    }

    @Test
    void diskReaderCanReadDocIdsWithoutPositions() throws Exception {
        InMemoryInvertedIndex index = index();
        new DiskIndexWriter().write(index, tempDir);

        try (DiskIndexReader reader = new DiskIndexReader(tempDir)) {
            PostingList postingList = reader.readDocIdPostingList("hello");

            assertEquals(Arrays.asList(1, 2), docIds(postingList));
            assertEquals(2, postingList.getPostings().get(0).getTermFrequency());
            assertEquals(0, postingList.getPostings().get(0).getPositions().length);
        }
    }

    @Test
    void mmapReaderRestoresPostingList() throws Exception {
        InMemoryInvertedIndex index = index();
        new DiskIndexWriter().write(index, tempDir);

        try (MMapIndexReader reader = new MMapIndexReader(tempDir)) {
            assertPostingList(reader.readPostingList("hello"));
            assertEquals(2, reader.getDocumentCount());
        }
    }

    @Test
    void mmapReaderUsesPagedMappingAcrossPageBoundaries() throws Exception {
        InMemoryInvertedIndex index = largeIndex();
        new DiskIndexWriter().write(index, tempDir);

        try (MMapIndexReader reader = new MMapIndexReader(tempDir, 4096, 2)) {
            PostingList postingList = reader.readPostingList("common");

            assertEquals(3000, postingList.size());
            assertArrayEquals(new int[] {0, 2}, postingList.getPostings().get(0).getPositions());
        }
    }

    @Test
    void diskSearchEngineSupportsBooleanAndPositionalQueries() throws Exception {
        InMemoryInvertedIndex index = searchIndex();
        new DiskIndexWriter().write(index, tempDir);

        try (DiskSearchEngine searchEngine = DiskSearchEngine.mmap(tempDir)) {
            assertEquals(Arrays.asList(1, 2), sortedDocIds(searchEngine.search("new", 10)));
            assertEquals(Arrays.asList(1), docIds(searchEngine.search("new ADJ york", 10)));
            assertEquals(Arrays.asList(2), docIds(searchEngine.search("(apple OR banana) AND NOT juice", 10)));
        }
    }

    @Test
    void diskSearchEngineStreamsQueryResults() throws Exception {
        InMemoryInvertedIndex index = searchIndex();
        new DiskIndexWriter().write(index, tempDir);

        try (DiskSearchEngine searchEngine = DiskSearchEngine.mmap(tempDir);
             PostingCursor cursor = searchEngine.openCursor("(apple OR banana) AND NOT juice")) {
            assertTrue(cursor.next());
            assertEquals(2, cursor.docId());
            assertFalse(cursor.next());
        }
    }

    @Test
    void diskPostingCursorReadsCurrentPostingOnly() throws Exception {
        InMemoryInvertedIndex index = index();
        new DiskIndexWriter().write(index, tempDir);

        try (MMapIndexReader reader = new MMapIndexReader(tempDir);
             PostingCursor cursor = reader.openCursor("hello", false)) {
            assertTrue(cursor.next());
            assertEquals(1, cursor.docId());
            assertEquals(2, cursor.termFrequency());
            assertEquals(0, cursor.positions().length);
            assertTrue(cursor.next());
            assertEquals(2, cursor.docId());
            assertFalse(cursor.next());
        }
    }

    @Test
    void diskPostingCursorAdvanceToUsesCompressedOffsetsCorrectly() throws Exception {
        InMemoryInvertedIndex index = largeIndex();
        new DiskIndexWriter().write(index, tempDir);

        try (MMapIndexReader reader = new MMapIndexReader(tempDir, 4096, 2);
             PostingCursor cursor = reader.openCursor("common", true)) {
            assertTrue(cursor.advanceTo(2500));
            assertEquals(2500, cursor.docId());
            assertEquals(2, cursor.termFrequency());
            assertArrayEquals(new int[] {0, 2}, cursor.positions());
            assertTrue(cursor.advanceTo(2999));
            assertEquals(2999, cursor.docId());
        }
    }

    private static InMemoryInvertedIndex index() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(Arrays.asList(
                new Document(1, "doc1", "hello world hello"),
                new Document(2, "doc2", "world hello")
        ));
        return index;
    }

    private static InMemoryInvertedIndex searchIndex() {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(Arrays.asList(
                new Document(1, "doc1", "new york city apple juice"),
                new Document(2, "doc2", "new big york banana"),
                new Document(3, "doc3", "apple banana juice")
        ));
        return index;
    }

    private static InMemoryInvertedIndex largeIndex() {
        List<Document> documents = new ArrayList<>();
        for (int docId = 1; docId <= 3000; docId++) {
            documents.add(new Document(docId, "doc" + docId, "common filler" + docId + " common"));
        }
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(documents);
        return index;
    }

    private static void assertPostingList(PostingList postingList) {
        assertEquals(Arrays.asList(1, 2), docIds(postingList));
        Posting first = postingList.getPostings().get(0);
        assertEquals(2, first.getTermFrequency());
        assertArrayEquals(new int[] {0, 2}, first.getPositions());
        Posting second = postingList.getPostings().get(1);
        assertEquals(1, second.getTermFrequency());
        assertArrayEquals(new int[] {1}, second.getPositions());
    }

    private static List<Integer> docIds(PostingList postingList) {
        return postingList.getPostings().stream().map(Posting::getDocId).collect(Collectors.toList());
    }

    private static List<Integer> docIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocId).collect(Collectors.toList());
    }

    private static List<Integer> sortedDocIds(List<SearchResult> results) {
        return results.stream().map(SearchResult::getDocId).sorted().collect(Collectors.toList());
    }
}
