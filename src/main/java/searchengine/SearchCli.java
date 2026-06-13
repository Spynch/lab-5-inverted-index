package searchengine;

import searchengine.compression.IndexCompressionMode;
import searchengine.compression.CompressionAnalyzer;
import searchengine.document.BeirDocumentLoader;
import searchengine.document.DocumentLoader;
import searchengine.document.TxtFolderDocumentLoader;
import searchengine.document.WikipediaDumpDocumentLoader;
import searchengine.document.WikipediaDumpPageReader;
import searchengine.document.WikipediaSourcePage;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.ranking.SearchResult;
import searchengine.storage.DiskIndexWriter;
import searchengine.storage.DiskSearchEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SearchCli {
    private SearchCli() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config.help) {
            printUsage();
            return;
        }
        if (config.sourcePageId != null) {
            printSourcePage(config.sourceDumpPath(), config.sourcePageId);
            return;
        }

        Path indexDirectory = config.indexDirectory;
        if (config.existingIndex) {
            requireDirectory(indexDirectory, "--index-dir");
        } else {
            DocumentLoader loader = config.loader();
            if (indexDirectory == null) {
                indexDirectory = Files.createTempDirectory("searchengine-index");
                System.out.println("Index directory: " + indexDirectory);
            }
            buildIndex(loader, indexDirectory, config.compressionMode, config.compareCompression);
        }

        try (DiskSearchEngine searchEngine = config.useDiskReader
                ? DiskSearchEngine.disk(indexDirectory)
                : DiskSearchEngine.mmap(indexDirectory)) {
            if (config.query != null) {
                runQuery(searchEngine, config.query, config.topK, config.rankResults, config.showDocuments,
                        config.showSource, config.sourceDumpPath());
            } else {
                runPrompt(searchEngine, config.topK, config.rankResults, config.showDocuments,
                        config.showSource, config.sourceDumpPath());
            }
        }
    }

    private static void buildIndex(DocumentLoader loader, Path indexDirectory,
                                   IndexCompressionMode compressionMode, boolean compareCompression) throws IOException {
        System.out.println("Building index...");
        long started = System.currentTimeMillis();
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(loader.load());
        if (compareCompression) {
            CompressionAnalyzer.printReport(index, System.out);
        }
        new DiskIndexWriter(compressionMode).write(index, indexDirectory);
        long elapsed = System.currentTimeMillis() - started;
        System.out.println("Indexed " + index.getDocumentCount() + " documents, "
                + index.getIndex().size() + " terms in " + elapsed + " ms.");
    }

    private static void runPrompt(DiskSearchEngine searchEngine, int topK, boolean rankResults,
                                  boolean showDocuments, boolean showSource, Path sourceDump) throws IOException {
        System.out.println("Enter query. Operators: AND, OR, NOT, ADJ/EDGE, NEAR/k. "
                + "Commands: :open <page-id>, :help, :quit.");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("search> ");
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            String query = stripLeadingBom(line).trim();
            if (query.isEmpty()) {
                continue;
            }
            if (":quit".equalsIgnoreCase(query) || ":q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }
            if (":help".equalsIgnoreCase(query)) {
                printQueryHelp();
                continue;
            }
            if (query.regionMatches(true, 0, ":open ", 0, 6)) {
                String pageId = query.substring(6).trim();
                if (pageId.isEmpty()) {
                    System.out.println("Usage: :open <page-id>");
                } else if (sourceDump == null) {
                    System.out.println("Configure --source-wiki <dump.xml> to open source pages.");
                } else {
                    printSourcePage(sourceDump, pageId);
                }
                continue;
            }
            try {
                runQuery(searchEngine, query, topK, rankResults, showDocuments, showSource, sourceDump);
            } catch (RuntimeException e) {
                System.out.println("Invalid query: " + e.getMessage());
            }
        }
    }

    private static void runQuery(DiskSearchEngine searchEngine, String query, int topK, boolean rankResults,
                                 boolean showDocuments, boolean showSource, Path sourceDump) throws IOException {
        long started = System.nanoTime();
        List<SearchResult> results = showDocuments
                ? searchEngine.searchDetailed(query, topK, rankResults)
                : searchEngine.search(query, topK, rankResults);
        long elapsedNanos = System.nanoTime() - started;
        Map<String, WikipediaSourcePage> sourcePages = showSource
                ? findSourcePages(sourceDump, results)
                : Collections.emptyMap();
        printResults(results, elapsedNanos, showDocuments, showSource, sourcePages);
    }

    private static void printResults(List<SearchResult> results, long elapsedNanos, boolean showDocuments,
                                     boolean showSource, Map<String, WikipediaSourcePage> sourcePages) {
        double elapsedMs = elapsedNanos / 1_000_000.0;
        if (results.isEmpty()) {
            System.out.printf(Locale.ROOT, "No results (%.3f ms).%n", elapsedMs);
            return;
        }
        System.out.printf(Locale.ROOT, "Found %d result(s) in %.3f ms:%n", results.size(), elapsedMs);
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            System.out.printf(Locale.ROOT, "%2d. score=%.6f docId=%d page=%s%n",
                    i + 1, result.getScore(), result.getDocId(), result.getExternalId());
            if (showDocuments) {
                for (Map.Entry<String, int[]> entry : result.getTermPositions().entrySet()) {
                    System.out.println("    term=" + entry.getKey() + " positions=" + formatPositions(entry.getValue()));
                }
                if (!result.getSnippet().isEmpty()) {
                    System.out.println("    snippet: " + result.getSnippet());
                }
            }
            if (showSource) {
                WikipediaSourcePage page = sourcePages.get(result.getExternalId());
                if (page == null) {
                    System.out.println("    source: page not found in dump");
                } else {
                    printSourcePage(page, "    ");
                }
            }
        }
    }

    private static Map<String, WikipediaSourcePage> findSourcePages(
            Path sourceDump, List<SearchResult> results) throws IOException {
        if (sourceDump == null) {
            throw new IllegalArgumentException("--show-source requires --source-wiki or --wiki");
        }
        Set<String> pageIds = new LinkedHashSet<>();
        for (SearchResult result : results) {
            pageIds.add(result.getExternalId());
        }
        return WikipediaDumpPageReader.findByIds(sourceDump, pageIds);
    }

    private static void printSourcePage(Path sourceDump, String pageId) throws IOException {
        WikipediaSourcePage page = WikipediaDumpPageReader.findById(sourceDump, pageId);
        if (page == null) {
            System.out.println("Wikipedia page " + pageId + " was not found in " + sourceDump);
            return;
        }
        printSourcePage(page, "");
    }

    private static void printSourcePage(WikipediaSourcePage page, String indent) {
        System.out.println(indent + "source-page-id: " + page.getPageId());
        System.out.println(indent + "source-title: " + page.getTitle());
        System.out.println(indent + "source-text:");
        page.getText().lines().forEach(line -> System.out.println(indent + "  " + line));
    }

    private static String stripLeadingBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static String formatPositions(int[] positions) {
        int limit = Math.min(32, positions.length);
        String prefix = Arrays.toString(Arrays.copyOf(positions, limit));
        if (positions.length <= limit) {
            return prefix;
        }
        return prefix.substring(0, prefix.length() - 1) + ", ...] (total=" + positions.length + ")";
    }

    private static void printQueryHelp() {
        System.out.println("Examples:");
        System.out.println("  information AND retrieval");
        System.out.println("  (apple OR banana) AND NOT juice");
        System.out.println("  new ADJ york");
        System.out.println("  new EDGE york");
        System.out.println("  graph NEAR/5 search");
        System.out.println("  :open 12345");
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -cp target/benchmarks.jar searchengine.SearchCli --txt <folder> --index-dir <dir>");
        System.out.println("  java -cp target/benchmarks.jar searchengine.SearchCli --beir <corpus.jsonl> --index-dir <dir>");
        System.out.println("  java -cp target/benchmarks.jar searchengine.SearchCli --wiki <dump.xml> --max-docs <n> --index-dir <dir>");
        System.out.println("  java -cp target/benchmarks.jar searchengine.SearchCli --index-dir <dir>");
        System.out.println("  java -cp target/benchmarks.jar searchengine.SearchCli "
                + "--source-wiki <dump.xml> --page-id <id>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --top-k <n>       Number of results to print, default 10");
        System.out.println("  --limit <n>       Alias for --top-k");
        System.out.println("  --query <query>   Execute one query and exit");
        System.out.println("  --show-docs       Print snippets and positions of matched terms");
        System.out.println("  --show-source     Resolve result page ids and print full source text");
        System.out.println("  --source-wiki <dump.xml> Wikipedia dump used by --show-source, --page-id and :open");
        System.out.println("  --page-id <id>    Print one source Wikipedia page and exit");
        System.out.println("  --no-ranking      Preserve docId order and report score=0");
        System.out.println("  --reader <mmap|disk> Reader implementation, default mmap");
        System.out.println("  --compression <mode> none|delta|varbyte|delta-varbyte|bitpacking|");
        System.out.println("                       delta-bitpacking|pfor|delta-pfor, default delta-varbyte");
        System.out.println("  --compare-compression Print size/speed diagnostics for all codecs while building");
        System.out.println("  --max-docs <n>    Wikipedia document limit, 0 means no limit");
    }

    private static void requireDirectory(Path path, String option) {
        if (path == null || !Files.isDirectory(path)) {
            throw new IllegalArgumentException(option + " must point to an existing index directory");
        }
    }

    private static final class Config {
        private Path txtFolder;
        private Path beirCorpus;
        private Path wikiDump;
        private Path sourceWikiDump;
        private Path indexDirectory;
        private int maxDocuments;
        private int topK = 10;
        private boolean useDiskReader;
        private boolean existingIndex = true;
        private boolean help;
        private String query;
        private boolean showDocuments;
        private boolean showSource;
        private String sourcePageId;
        private boolean rankResults = true;
        private IndexCompressionMode compressionMode = IndexCompressionMode.DELTA_VARBYTE;
        private boolean compareCompression;

        private static Config parse(String[] args) {
            Config config = new Config();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help":
                    case "-h":
                        config.help = true;
                        break;
                    case "--txt":
                        config.txtFolder = Path.of(value(args, ++i, arg));
                        config.existingIndex = false;
                        break;
                    case "--beir":
                        config.beirCorpus = Path.of(value(args, ++i, arg));
                        config.existingIndex = false;
                        break;
                    case "--wiki":
                        config.wikiDump = Path.of(value(args, ++i, arg));
                        config.existingIndex = false;
                        break;
                    case "--source-wiki":
                        config.sourceWikiDump = Path.of(value(args, ++i, arg));
                        break;
                    case "--index-dir":
                        config.indexDirectory = Path.of(value(args, ++i, arg));
                        break;
                    case "--max-docs":
                        config.maxDocuments = Integer.parseInt(value(args, ++i, arg));
                        break;
                    case "--top-k":
                    case "--limit":
                        config.topK = Integer.parseInt(value(args, ++i, arg));
                        break;
                    case "--query":
                        config.query = value(args, ++i, arg);
                        break;
                    case "--show-docs":
                        config.showDocuments = true;
                        break;
                    case "--show-source":
                        config.showSource = true;
                        break;
                    case "--page-id":
                        config.sourcePageId = value(args, ++i, arg);
                        break;
                    case "--no-ranking":
                        config.rankResults = false;
                        break;
                    case "--reader":
                        String reader = value(args, ++i, arg);
                        if ("disk".equalsIgnoreCase(reader)) {
                            config.useDiskReader = true;
                        } else if ("mmap".equalsIgnoreCase(reader)) {
                            config.useDiskReader = false;
                        } else {
                            throw new IllegalArgumentException("--reader must be mmap or disk");
                        }
                        break;
                    case "--compression":
                        config.compressionMode = IndexCompressionMode.fromId(value(args, ++i, arg));
                        break;
                    case "--compare-compression":
                        config.compareCompression = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            config.validate();
            return config;
        }

        private void validate() {
            if (help) {
                return;
            }
            if (topK <= 0) {
                throw new IllegalArgumentException("--top-k must be positive");
            }
            if (maxDocuments < 0) {
                throw new IllegalArgumentException("--max-docs must be non-negative");
            }
            if (sourcePageId != null) {
                if (sourcePageId.isEmpty()) {
                    throw new IllegalArgumentException("--page-id must not be empty");
                }
                if (sourceDumpPath() == null) {
                    throw new IllegalArgumentException("--page-id requires --source-wiki or --wiki");
                }
                return;
            }
            int datasets = count(txtFolder) + count(beirCorpus) + count(wikiDump);
            if (datasets > 1) {
                throw new IllegalArgumentException("Specify only one dataset source");
            }
            if (datasets == 0 && indexDirectory == null) {
                throw new IllegalArgumentException("Specify a dataset source or --index-dir for an existing index");
            }
            if (showSource && sourceDumpPath() == null) {
                throw new IllegalArgumentException("--show-source requires --source-wiki or --wiki");
            }
        }

        private Path sourceDumpPath() {
            return sourceWikiDump == null ? wikiDump : sourceWikiDump;
        }

        private DocumentLoader loader() {
            if (txtFolder != null) {
                return new TxtFolderDocumentLoader(txtFolder);
            }
            if (beirCorpus != null) {
                return new BeirDocumentLoader(beirCorpus);
            }
            if (wikiDump != null) {
                return new WikipediaDumpDocumentLoader(wikiDump, maxDocuments);
            }
            throw new IllegalStateException("No dataset source configured");
        }

        private static int count(Path path) {
            return path == null ? 0 : 1;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
