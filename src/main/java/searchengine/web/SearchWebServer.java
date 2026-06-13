package searchengine.web;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SearchWebServer {
    private SearchWebServer() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config.help) {
            printUsage();
            return;
        }

        SearchHttpServer server = SearchHttpServer.open(
                new InetSocketAddress(config.host, config.port),
                config.indexDirectory,
                config.sourceDump,
                config.diskReader);
        AtomicBoolean closed = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> close(server, closed), "search-web-shutdown"));
        server.start();

        System.out.println("Search web UI: http://" + config.host + ":" + server.getPort() + "/");
        System.out.println("Index: " + config.indexDirectory.toAbsolutePath());
        System.out.println("Wikipedia source: " + config.sourceDump.toAbsolutePath());
        new CountDownLatch(1).await();
    }

    private static void close(SearchHttpServer server, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            try {
                server.close();
            } catch (Exception e) {
                System.err.println("Failed to stop search web server: " + e.getMessage());
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -cp target/benchmarks.jar searchengine.web.SearchWebServer \\");
        System.out.println("    --index-dir <dir> --source-wiki <dump.xml> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --host <host>        Bind host, default 127.0.0.1");
        System.out.println("  --port <port>        Bind port, default 8080");
        System.out.println("  --reader <mmap|disk> Reader implementation, default mmap");
    }

    private static final class Config {
        private Path indexDirectory;
        private Path sourceDump;
        private String host = "127.0.0.1";
        private int port = 8080;
        private boolean diskReader;
        private boolean help;

        private static Config parse(String[] args) {
            Config config = new Config();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                switch (argument) {
                    case "--help":
                    case "-h":
                        config.help = true;
                        break;
                    case "--index-dir":
                        config.indexDirectory = pathValue(args, ++i, argument);
                        break;
                    case "--source-wiki":
                        config.sourceDump = pathValue(args, ++i, argument);
                        break;
                    case "--host":
                        config.host = cleanValue(args, ++i, argument);
                        break;
                    case "--port":
                        config.port = Integer.parseInt(cleanValue(args, ++i, argument));
                        break;
                    case "--reader":
                        String reader = cleanValue(args, ++i, argument);
                        if ("disk".equalsIgnoreCase(reader)) {
                            config.diskReader = true;
                        } else if ("mmap".equalsIgnoreCase(reader)) {
                            config.diskReader = false;
                        } else {
                            throw new IllegalArgumentException("--reader must be mmap or disk");
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + argument);
                }
            }
            config.validate();
            return config;
        }

        private void validate() {
            if (help) {
                return;
            }
            if (indexDirectory == null || !Files.isDirectory(indexDirectory)) {
                throw new IllegalArgumentException("--index-dir must point to an existing index directory");
            }
            if (sourceDump == null || !Files.isRegularFile(sourceDump)) {
                throw new IllegalArgumentException("--source-wiki must point to a Wikipedia XML dump");
            }
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("--host must not be empty");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("--port must be between 0 and 65535");
            }
        }

        private static Path pathValue(String[] args, int index, String option) {
            String value = cleanValue(args, index, option);
            for (int offset = 0; offset < value.length(); offset++) {
                if (Character.isISOControl(value.charAt(offset))) {
                    throw new IllegalArgumentException(option + " contains a line break or control character");
                }
            }
            return Path.of(value);
        }

        private static String cleanValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            String value = args[index].trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return value;
        }
    }
}
