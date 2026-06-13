package searchengine.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import searchengine.document.WikipediaDumpDocumentLoader;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.storage.DiskIndexWriter;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchHttpServerTest {
    @TempDir
    Path tempDir;

    @Test
    void servesSearchPaginationAndSourceDocuments() throws Exception {
        Path dump = tempDir.resolve("wiki.xml");
        StringBuilder xml = new StringBuilder("<mediawiki>");
        for (int index = 0; index < 12; index++) {
            int pageId = 100 + index;
            String body = index == 5
                    ? "common '''body''' [[Target|readable link]] &lt;ref&gt;hidden&lt;/ref&gt;"
                    : "common body " + index;
            xml.append("<page><title>Page ").append(index).append("</title><id>")
                    .append(pageId)
                    .append("</id><revision><id>").append(1000 + index)
                    .append("</id><text>").append(body)
                    .append("</text></revision></page>");
        }
        xml.append("</mediawiki>");
        Files.writeString(dump, xml, StandardCharsets.UTF_8);

        Path indexDirectory = tempDir.resolve("index");
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(new WikipediaDumpDocumentLoader(dump).load());
        new DiskIndexWriter().write(index, indexDirectory);

        try (SearchHttpServer server = SearchHttpServer.open(
                new InetSocketAddress("127.0.0.1", 0), indexDirectory, dump, false)) {
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> page = get(client, base + "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("id=\"searchForm\""));

            String query = URLEncoder.encode("common", StandardCharsets.UTF_8);
            HttpResponse<String> first = get(client,
                    base + "/api/search?q=" + query + "&offset=0&limit=10&ranking=false");
            assertEquals(200, first.statusCode());
            assertTrue(first.body().contains("\"total\":12"));
            assertTrue(first.body().contains("\"hasMore\":true"));
            assertTrue(first.body().contains("\"pageId\":\"100\""));

            HttpResponse<String> second = get(client,
                    base + "/api/search?q=" + query + "&offset=10&limit=10&ranking=false");
            assertEquals(200, second.statusCode());
            assertTrue(second.body().contains("\"hasMore\":false"));
            assertTrue(second.body().contains("\"pageId\":\"111\""));

            HttpResponse<String> document = get(client, base + "/api/document?pageId=105");
            assertEquals(200, document.statusCode());
            assertTrue(document.body().contains("\"title\":\"Page 5\""));
            assertTrue(document.body().contains("\"text\":\"common body readable link\""));
            assertTrue(!document.body().contains("[[Target"));
            assertTrue(!document.body().contains("hidden"));
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
