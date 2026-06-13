package searchengine.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import searchengine.document.WikipediaDumpPageReader;
import searchengine.document.WikipediaMarkupCleaner;
import searchengine.document.WikipediaSourcePage;
import searchengine.ranking.SearchPage;
import searchengine.ranking.SearchResult;
import searchengine.storage.DiskSearchEngine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SearchHttpServer implements AutoCloseable {
    private static final int MAX_PAGE_SIZE = 50;

    private final HttpServer server;
    private final ExecutorService executor;
    private final DiskSearchEngine searchEngine;
    private final SourcePageCache sourcePages;

    private SearchHttpServer(HttpServer server, ExecutorService executor,
                             DiskSearchEngine searchEngine, Path sourceDump) {
        this.server = server;
        this.executor = executor;
        this.searchEngine = searchEngine;
        this.sourcePages = new SourcePageCache(sourceDump, 32);
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/document", new DocumentHandler());
        server.createContext("/api/config", exchange -> {
            if (!requireGet(exchange)) {
                return;
            }
            sendJson(exchange, 200, "{\"sourceDocuments\":true,\"pageSize\":10,\"maxPageSize\":50}");
        });
        server.createContext("/", new StaticHandler());
    }

    public static SearchHttpServer open(InetSocketAddress address, Path indexDirectory,
                                        Path sourceDump, boolean diskReader) throws IOException {
        DiskSearchEngine engine = diskReader
                ? DiskSearchEngine.disk(indexDirectory)
                : DiskSearchEngine.mmap(indexDirectory);
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
        try {
            HttpServer server = HttpServer.create(address, 0);
            server.setExecutor(executor);
            return new SearchHttpServer(server, executor, engine, sourceDump);
        } catch (IOException | RuntimeException e) {
            executor.shutdownNow();
            engine.close();
            throw e;
        }
    }

    public void start() {
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        executor.shutdownNow();
        searchEngine.close();
    }

    private final class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!requireGet(exchange)) {
                    return;
                }
                Map<String, String> parameters = queryParameters(exchange);
                String query = required(parameters, "q").trim();
                if (query.isEmpty()) {
                    throw new IllegalArgumentException("Search query must not be empty");
                }
                int offset = integer(parameters, "offset", 0, 0, Integer.MAX_VALUE);
                int limit = integer(parameters, "limit", 10, 1, MAX_PAGE_SIZE);
                boolean ranking = bool(parameters, "ranking", true);

                long started = System.nanoTime();
                SearchPage page = searchEngine.searchPage(query, offset, limit, ranking, true);
                double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
                sendJson(exchange, 200, searchJson(query, ranking, elapsedMs, page));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, errorJson(e.getMessage()));
            } catch (RuntimeException e) {
                sendJson(exchange, 400, errorJson(e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, errorJson("Search failed: " + e.getMessage()));
            }
        }
    }

    private final class DocumentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!requireGet(exchange)) {
                    return;
                }
                String pageId = required(queryParameters(exchange), "pageId").trim();
                if (pageId.isEmpty()) {
                    throw new IllegalArgumentException("pageId must not be empty");
                }
                WikipediaSourcePage page = sourcePages.get(pageId);
                if (page == null) {
                    sendJson(exchange, 404, errorJson("Wikipedia page " + pageId + " was not found"));
                    return;
                }
                sendJson(exchange, 200, documentJson(page));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, errorJson(e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, errorJson("Document loading failed: " + e.getMessage()));
            }
        }
    }

    private static final class StaticHandler implements HttpHandler {
        private static final Map<String, Resource> RESOURCES = resources();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!requireGet(exchange)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Resource resource = RESOURCES.get(path);
            if (resource == null) {
                sendText(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            byte[] content = readResource(resource.classpath);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            sendBytes(exchange, 200, resource.contentType, content);
        }

        private static Map<String, Resource> resources() {
            Map<String, Resource> resources = new LinkedHashMap<>();
            resources.put("/", new Resource("/web/index.html", "text/html; charset=utf-8"));
            resources.put("/index.html", new Resource("/web/index.html", "text/html; charset=utf-8"));
            resources.put("/styles.css", new Resource("/web/styles.css", "text/css; charset=utf-8"));
            resources.put("/app.js", new Resource("/web/app.js", "text/javascript; charset=utf-8"));
            return resources;
        }
    }

    private static String searchJson(String query, boolean ranking, double elapsedMs, SearchPage page) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{')
                .append("\"query\":").append(quote(query)).append(',')
                .append("\"ranking\":").append(ranking).append(',')
                .append("\"elapsedMs\":").append(String.format(java.util.Locale.ROOT, "%.3f", elapsedMs)).append(',')
                .append("\"total\":").append(page.getTotalMatches()).append(',')
                .append("\"offset\":").append(page.getOffset()).append(',')
                .append("\"limit\":").append(page.getLimit()).append(',')
                .append("\"hasMore\":").append(page.hasMore()).append(',')
                .append("\"results\":[");
        for (int i = 0; i < page.getResults().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendResult(json, page.getResults().get(i));
        }
        return json.append("]}").toString();
    }

    private static void appendResult(StringBuilder json, SearchResult result) {
        json.append('{')
                .append("\"docId\":").append(result.getDocId()).append(',')
                .append("\"pageId\":").append(quote(result.getExternalId())).append(',')
                .append("\"score\":").append(Double.toString(result.getScore())).append(',')
                .append("\"snippet\":").append(quote(result.getSnippet())).append(',')
                .append("\"terms\":{");
        int termIndex = 0;
        for (Map.Entry<String, int[]> entry : result.getTermPositions().entrySet()) {
            if (termIndex++ > 0) {
                json.append(',');
            }
            int[] positions = entry.getValue();
            json.append(quote(entry.getKey())).append(":{\"total\":").append(positions.length)
                    .append(",\"positions\":[");
            int count = Math.min(positions.length, 32);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(positions[i]);
            }
            json.append("]}");
        }
        json.append("}}");
    }

    private static String documentJson(WikipediaSourcePage page) {
        return "{\"pageId\":" + quote(page.getPageId())
                + ",\"title\":" + quote(page.getTitle())
                + ",\"text\":" + quote(WikipediaMarkupCleaner.toPlainText(page.getText())) + "}";
    }

    private static String errorJson(String message) {
        return "{\"error\":" + quote(message == null ? "Unknown error" : message) + "}";
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static Map<String, String> queryParameters(HttpExchange exchange) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            parameters.put(decode(key), decode(value));
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing query parameter: " + name);
        }
        return value;
    }

    private static int integer(Map<String, String> parameters, String name,
                               int defaultValue, int minimum, int maximum) {
        String value = parameters.get(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static boolean bool(Map<String, String> parameters, String name, boolean defaultValue) {
        String value = parameters.get(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static boolean requireGet(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendJson(exchange, 405, errorJson("Only GET is supported"));
            return false;
        }
        return true;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream input = SearchHttpServer.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing classpath resource: " + path);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendText(exchange, status, "application/json; charset=utf-8", json);
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, String text)
            throws IOException {
        sendBytes(exchange, status, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] content)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static final class SourcePageCache {
        private final Path dumpPath;
        private final Map<String, WikipediaSourcePage> pages;

        private SourcePageCache(Path dumpPath, int maxEntries) {
            this.dumpPath = dumpPath;
            this.pages = new LinkedHashMap<String, WikipediaSourcePage>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WikipediaSourcePage> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        private synchronized WikipediaSourcePage get(String pageId) throws IOException {
            WikipediaSourcePage cached = pages.get(pageId);
            if (cached != null) {
                return cached;
            }
            WikipediaSourcePage loaded = WikipediaDumpPageReader.findById(dumpPath, pageId);
            if (loaded != null) {
                pages.put(pageId, loaded);
            }
            return loaded;
        }
    }

    private static final class Resource {
        private final String classpath;
        private final String contentType;

        private Resource(String classpath, String contentType) {
            this.classpath = classpath;
            this.contentType = contentType;
        }
    }
}
