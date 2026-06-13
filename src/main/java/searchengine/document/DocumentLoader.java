package searchengine.document;

import java.util.Iterator;

public interface DocumentLoader {
    Iterator<Document> load();
}
