package searchengine.document;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WikipediaDumpPageReader {
    private WikipediaDumpPageReader() {
    }

    public static WikipediaSourcePage findById(Path dumpPath, String pageId) throws IOException {
        Map<String, WikipediaSourcePage> pages = findByIds(dumpPath, Collections.singleton(pageId));
        return pages.get(pageId);
    }

    public static Map<String, WikipediaSourcePage> findByIds(Path dumpPath, Collection<String> pageIds)
            throws IOException {
        Objects.requireNonNull(dumpPath, "dumpPath");
        Objects.requireNonNull(pageIds, "pageIds");
        Set<String> remaining = new LinkedHashSet<>();
        for (String pageId : pageIds) {
            if (pageId != null && !pageId.isEmpty()) {
                remaining.add(pageId);
            }
        }
        if (remaining.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, WikipediaSourcePage> found = new LinkedHashMap<>();
        XMLStreamReader reader = null;
        try (InputStream input = Files.newInputStream(dumpPath)) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            disableExternalEntities(factory);
            reader = factory.createXMLStreamReader(input);
            PageState page = null;
            while (reader.hasNext() && !remaining.isEmpty()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("page".equals(name)) {
                        page = new PageState();
                    } else if (page != null) {
                        page.start(name);
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && page != null) {
                    page.characters(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && page != null) {
                    String name = reader.getLocalName();
                    if ("page".equals(name)) {
                        WikipediaSourcePage sourcePage = page.toSourcePage();
                        if (sourcePage != null && remaining.remove(sourcePage.getPageId())) {
                            found.put(sourcePage.getPageId(), sourcePage);
                        }
                        page = null;
                    } else {
                        page.end(name);
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Failed to parse Wikipedia dump: " + dumpPath, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // The input stream is closed by try-with-resources.
                }
            }
        }

        Map<String, WikipediaSourcePage> ordered = new LinkedHashMap<>();
        for (String pageId : pageIds) {
            WikipediaSourcePage page = found.get(pageId);
            if (page != null) {
                ordered.put(pageId, page);
            }
        }
        return ordered;
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

    private static final class PageState {
        private final StringBuilder title = new StringBuilder();
        private final StringBuilder pageId = new StringBuilder();
        private final StringBuilder text = new StringBuilder();
        private int depth = 1;
        private int captureDepth = -1;
        private String capture;

        void start(String name) {
            depth++;
            if (capture != null) {
                return;
            }
            if (depth == 2 && ("title".equals(name) || "id".equals(name))) {
                capture = name;
                captureDepth = depth;
            } else if ("text".equals(name)) {
                capture = name;
                captureDepth = depth;
            }
        }

        void characters(String value) {
            if ("title".equals(capture)) {
                title.append(value);
            } else if ("id".equals(capture)) {
                pageId.append(value);
            } else if ("text".equals(capture)) {
                text.append(value);
            }
        }

        void end(String name) {
            if (depth == captureDepth && name.equals(capture)) {
                capture = null;
                captureDepth = -1;
            }
            depth--;
        }

        WikipediaSourcePage toSourcePage() {
            String id = pageId.toString().trim();
            if (id.isEmpty()) {
                return null;
            }
            return new WikipediaSourcePage(id, title.toString().trim(), text.toString());
        }
    }
}
