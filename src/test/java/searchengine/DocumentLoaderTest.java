package searchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import searchengine.document.BeirDocumentLoader;
import searchengine.document.Document;
import searchengine.document.TxtFolderDocumentLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsTxtFilesFromFolder() throws Exception {
        Files.write(tempDir.resolve("b.txt"), "second".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("a.txt"), "first".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("skip.md"), "ignored".getBytes(StandardCharsets.UTF_8));

        Iterator<Document> documents = new TxtFolderDocumentLoader(tempDir).load();

        Document first = documents.next();
        Document second = documents.next();
        assertEquals(1, first.getDocId());
        assertEquals("a.txt", first.getExternalId());
        assertEquals("first", first.getText());
        assertEquals(2, second.getDocId());
        assertEquals("b.txt", second.getExternalId());
        assertFalse(documents.hasNext());
    }

    @Test
    void loadsBeirJsonlCorpus() throws Exception {
        Path corpus = tempDir.resolve("corpus.jsonl");
        Files.write(corpus, ("{\"_id\":\"d1\",\"title\":\"Title\",\"text\":\"Body\"}\n" +
                "{\"_id\":\"d2\",\"text\":\"Only body\"}\n").getBytes(StandardCharsets.UTF_8));

        Iterator<Document> documents = new BeirDocumentLoader(corpus).load();

        Document first = documents.next();
        Document second = documents.next();
        assertEquals("d1", first.getExternalId());
        assertEquals("Title Body", first.getText());
        assertEquals("d2", second.getExternalId());
        assertEquals("Only body", second.getText());
        assertFalse(documents.hasNext());
    }
}
