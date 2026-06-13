package searchengine.document;

import java.util.Objects;

public final class WikipediaSourcePage {
    private final String pageId;
    private final String title;
    private final String text;

    public WikipediaSourcePage(String pageId, String title, String text) {
        this.pageId = Objects.requireNonNull(pageId, "pageId");
        this.title = Objects.requireNonNull(title, "title");
        this.text = Objects.requireNonNull(text, "text");
    }

    public String getPageId() {
        return pageId;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }
}
