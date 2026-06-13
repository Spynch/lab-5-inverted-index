package searchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import searchengine.document.Document;
import searchengine.document.WikipediaDumpDocumentLoader;
import searchengine.document.WikipediaDumpPageReader;
import searchengine.document.WikipediaSourcePage;
import searchengine.index.InMemoryInvertedIndex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WikipediaDumpDocumentLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsWikipediaPagesFromXmlDump() throws Exception {
        Path dump = tempDir.resolve("wiki.xml");
        Files.write(dump, ("<mediawiki>" +
                "<page><title>First</title><id>10</id><revision><id>100</id><text>First body</text></revision></page>" +
                "<page><title>Second</title><id>20</id><revision><id>200</id><text>Second body</text></revision></page>" +
                "</mediawiki>").getBytes(StandardCharsets.UTF_8));

        Iterator<Document> documents = new WikipediaDumpDocumentLoader(dump).load();

        Document first = documents.next();
        Document second = documents.next();
        assertEquals(1, first.getDocId());
        assertEquals("10", first.getExternalId());
        assertEquals("First First body", first.getText());
        assertEquals(2, second.getDocId());
        assertEquals("20", second.getExternalId());
        assertEquals("Second Second body", second.getText());
        assertFalse(documents.hasNext());
    }

    @Test
    void respectsDocumentLimit() throws Exception {
        Path dump = tempDir.resolve("wiki.xml");
        Files.write(dump, ("<mediawiki>" +
                "<page><title>First</title><id>10</id><revision><text>First body</text></revision></page>" +
                "<page><title>Second</title><id>20</id><revision><text>Second body</text></revision></page>" +
                "</mediawiki>").getBytes(StandardCharsets.UTF_8));

        Iterator<Document> documents = new WikipediaDumpDocumentLoader(dump, 1).load();

        assertEquals("10", documents.next().getExternalId());
        assertFalse(documents.hasNext());
    }

    @Test
    void indexesTheSameReadableTextThatTheWebInterfaceDisplays() throws Exception {
        Path dump = tempDir.resolve("wiki.xml");
        Files.writeString(dump, "<mediawiki>"
                + "<page><title>История страны</title><id>10</id><revision><id>100</id>"
                + "<text>{{Карточка|служебный_терм=россии}}"
                + "Видимый текст об [[история России|истории России]]."
                + "&lt;ref&gt;скрытая сноска&lt;/ref&gt;</text></revision></page>"
                + "</mediawiki>", StandardCharsets.UTF_8);

        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(new WikipediaDumpDocumentLoader(dump).load());

        assertEquals(1, index.getDocumentFrequency("истории"));
        assertEquals(1, index.getDocumentFrequency("россии"));
        assertEquals(0, index.getDocumentFrequency("служебный"));
        assertEquals(0, index.getDocumentFrequency("сноска"));
        assertFalse(index.getDocumentMeta(1).getSnippet().contains("{{"));
    }

    @Test
    void findsSourcePagesByWikipediaPageId() throws Exception {
        Path dump = tempDir.resolve("wiki.xml");
        Files.write(dump, ("<mediawiki>" +
                "<page><title>First</title><id>10</id><revision><id>100</id><text>First body</text></revision></page>" +
                "<page><title>Second</title><id>20</id><revision><id>200</id><text>Second body</text></revision></page>" +
                "</mediawiki>").getBytes(StandardCharsets.UTF_8));

        Map<String, WikipediaSourcePage> pages =
                WikipediaDumpPageReader.findByIds(dump, Arrays.asList("20", "10", "404"));

        assertEquals(Arrays.asList("20", "10"), new ArrayList<>(pages.keySet()));
        assertEquals("Second", pages.get("20").getTitle());
        assertEquals("Second body", pages.get("20").getText());
        assertEquals("First body", WikipediaDumpPageReader.findById(dump, "10").getText());
        assertNull(WikipediaDumpPageReader.findById(dump, "404"));
    }
}
