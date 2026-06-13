package searchengine.document;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class WikipediaDumpDocumentLoader implements DocumentLoader {
    private final Path dumpPath;
    private final int maxDocuments;

    public WikipediaDumpDocumentLoader(Path dumpPath) {
        this(dumpPath, 0);
    }

    public WikipediaDumpDocumentLoader(Path dumpPath, int maxDocuments) {
        this.dumpPath = Objects.requireNonNull(dumpPath, "dumpPath");
        this.maxDocuments = maxDocuments;
    }

    @Override
    public Iterator<Document> load() {
        try {
            return new WikipediaIterator(dumpPath, maxDocuments);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to open Wikipedia dump: " + dumpPath, e);
        }
    }

    private static final class WikipediaIterator implements Iterator<Document> {
        private final InputStream input;
        private final XMLStreamReader reader;
        private final int maxDocuments;
        private int emittedDocuments;
        private int nextDocId = 1;
        private boolean finished;
        private Document next;

        private boolean inPage;
        private String currentElement;
        private StringBuilder title;
        private StringBuilder pageId;
        private StringBuilder text;
        private boolean pageIdCaptured;

        private WikipediaIterator(Path dumpPath, int maxDocuments) throws IOException, XMLStreamException {
            this.input = Files.newInputStream(dumpPath);
            XMLInputFactory factory = XMLInputFactory.newFactory();
            disableExternalEntities(factory);
            this.reader = factory.createXMLStreamReader(input);
            this.maxDocuments = maxDocuments;
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            if (finished) {
                return false;
            }
            next = readNext();
            return next != null;
        }

        @Override
        public Document next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Document result = next;
            next = null;
            return result;
        }

        private Document readNext() {
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        handleStart(reader.getLocalName());
                    } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                        appendCharacters(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        Document document = handleEnd(reader.getLocalName());
                        if (document != null) {
                            return document;
                        }
                    }
                }
                close();
                return null;
            } catch (XMLStreamException e) {
                closeQuietly();
                throw new IllegalStateException("Failed to parse Wikipedia dump", e);
            }
        }

        private void handleStart(String name) {
            if ("page".equals(name)) {
                inPage = true;
                currentElement = null;
                title = new StringBuilder();
                pageId = new StringBuilder();
                text = new StringBuilder();
                pageIdCaptured = false;
                return;
            }
            if (!inPage) {
                return;
            }
            if ("title".equals(name) || "text".equals(name)) {
                currentElement = name;
            } else if ("id".equals(name) && !pageIdCaptured) {
                currentElement = name;
            }
        }

        private void appendCharacters(String value) {
            if (!inPage || currentElement == null) {
                return;
            }
            if ("title".equals(currentElement)) {
                title.append(value);
            } else if ("id".equals(currentElement)) {
                pageId.append(value);
            } else if ("text".equals(currentElement)) {
                text.append(value);
            }
        }

        private Document handleEnd(String name) {
            if (!inPage) {
                return null;
            }
            if (name.equals(currentElement)) {
                if ("id".equals(name)) {
                    pageIdCaptured = true;
                }
                currentElement = null;
            }
            if ("page".equals(name)) {
                inPage = false;
                if (maxDocuments > 0 && emittedDocuments >= maxDocuments) {
                    close();
                    return null;
                }
                String titleValue = title.toString().trim();
                String idValue = pageId.toString().trim();
                String externalId = idValue.isEmpty() ? titleValue : idValue;
                if (externalId.isEmpty()) {
                    externalId = String.valueOf(nextDocId);
                }
                String readableText = WikipediaMarkupCleaner.toPlainText(text.toString());
                String documentText = titleValue.isEmpty()
                        ? readableText
                        : readableText.isEmpty() ? titleValue : titleValue + " " + readableText;
                Document document = new Document(nextDocId++, externalId, documentText);
                emittedDocuments++;
                if (maxDocuments > 0 && emittedDocuments >= maxDocuments) {
                    close();
                }
                return document;
            }
            return null;
        }

        private void close() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                reader.close();
                input.close();
            } catch (XMLStreamException e) {
                throw new IllegalStateException("Failed to close Wikipedia dump parser", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void closeQuietly() {
            try {
                close();
            } catch (RuntimeException ignored) {
                // Preserve the original parsing exception.
            }
        }

        private static void disableExternalEntities(XMLInputFactory factory) {
            setPropertyIfSupported(factory, XMLInputFactory.SUPPORT_DTD, false);
            setPropertyIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        }

        private static void setPropertyIfSupported(XMLInputFactory factory, String property, Object value) {
            try {
                factory.setProperty(property, value);
            } catch (IllegalArgumentException ignored) {
                // Some StAX implementations do not expose every security property.
            }
        }
    }
}
