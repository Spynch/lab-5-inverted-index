package searchengine.storage;

import searchengine.compression.IndexCompressionMode;

import java.util.Map;

final class DictionaryData {
    private final Map<String, DictionaryEntry> entries;
    private final IndexCompressionMode compressionMode;

    DictionaryData(Map<String, DictionaryEntry> entries, IndexCompressionMode compressionMode) {
        this.entries = entries;
        this.compressionMode = compressionMode;
    }

    Map<String, DictionaryEntry> getEntries() {
        return entries;
    }

    IndexCompressionMode getCompressionMode() {
        return compressionMode;
    }
}
