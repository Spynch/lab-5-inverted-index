package searchengine.storage;

import java.nio.file.Path;

final class DiskIndexFiles {
    static final String DICTIONARY = "dictionary.bin";
    static final String POSTINGS = "postings.bin";
    static final String POSITIONS = "positions.bin";
    static final String DOCUMENTS = "documents.bin";
    static final String META = "meta.json";

    private DiskIndexFiles() {
    }

    static Path dictionary(Path directory) {
        return directory.resolve(DICTIONARY);
    }

    static Path postings(Path directory) {
        return directory.resolve(POSTINGS);
    }

    static Path positions(Path directory) {
        return directory.resolve(POSITIONS);
    }

    static Path documents(Path directory) {
        return directory.resolve(DOCUMENTS);
    }

    static Path meta(Path directory) {
        return directory.resolve(META);
    }
}