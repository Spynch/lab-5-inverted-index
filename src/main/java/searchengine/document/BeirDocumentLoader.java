package searchengine.document;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class BeirDocumentLoader implements DocumentLoader {
    private final Path corpusJsonl;

    public BeirDocumentLoader(Path corpusJsonl) {
        this.corpusJsonl = Objects.requireNonNull(corpusJsonl, "corpusJsonl");
    }

    @Override
    public Iterator<Document> load() {
        try {
            return new BeirIterator(Files.newBufferedReader(corpusJsonl, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class BeirIterator implements Iterator<Document> {
        private final BufferedReader reader;
        private String nextLine;
        private int nextDocId = 1;
        private boolean closed;

        private BeirIterator(BufferedReader reader) {
            this.reader = reader;
            advance();
        }

        @Override
        public boolean hasNext() {
            return nextLine != null;
        }

        @Override
        public Document next() {
            if (nextLine == null) {
                throw new NoSuchElementException();
            }
            String line = nextLine;
            advance();
            String externalId = firstNonNull(extractJsonString(line, "_id"), String.valueOf(nextDocId));
            String title = firstNonNull(extractJsonString(line, "title"), "");
            String text = firstNonNull(extractJsonString(line, "text"), "");
            String fullText = title.isEmpty() ? text : title + " " + text;
            return new Document(nextDocId++, externalId, fullText);
        }

        private void advance() {
            if (closed) {
                nextLine = null;
                return;
            }
            try {
                nextLine = reader.readLine();
                if (nextLine == null) {
                    reader.close();
                    closed = true;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static String firstNonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    static String extractJsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int fieldIndex = json.indexOf(needle);
        if (fieldIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', fieldIndex + needle.length());
        if (colon < 0) {
            return null;
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int i = quote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                value.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException ignored) {
                                value.append('u');
                                break;
                            }
                        }
                        value.append('u');
                        break;
                    default: value.append(c); break;
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }
}
