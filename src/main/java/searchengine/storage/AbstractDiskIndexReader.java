package searchengine.storage;

import searchengine.compression.IndexCompressionMode;
import searchengine.document.DocumentMeta;
import searchengine.index.PostingList;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

abstract class AbstractDiskIndexReader implements PostingListReader {
    private final Map<String, DictionaryEntry> dictionary;
    private final Map<Integer, DocumentMeta> documents;
    private final IndexCompressionMode compressionMode;

    AbstractDiskIndexReader(Path directory) throws IOException {
        try (DataInputStream dictionaryIn = new DataInputStream(new BufferedInputStream(Files.newInputStream(DiskIndexFiles.dictionary(directory))));
             DataInputStream documentsIn = new DataInputStream(new BufferedInputStream(Files.newInputStream(DiskIndexFiles.documents(directory))))) {
            DictionaryData dictionaryData = DiskIndexCodec.readDictionary(dictionaryIn);
            this.dictionary = dictionaryData.getEntries();
            this.compressionMode = dictionaryData.getCompressionMode();
            this.documents = DiskIndexCodec.readDocuments(documentsIn);
        }
    }

    @Override
    public final PostingList readPostingList(String term) throws IOException {
        if (term == null) {
            return PostingList.empty("");
        }
        String normalized = term.toLowerCase(Locale.ROOT);
        DictionaryEntry entry = dictionary.get(normalized);
        if (entry == null) {
            return PostingList.empty(normalized);
        }
        return DiskIndexCodec.decodePostingList(normalized, readPostingsSlice(entry), readPositionsSlice(entry),
                entry.getDocumentFrequency(), compressionMode);
    }

    @Override
    public final PostingList readDocIdPostingList(String term) throws IOException {
        if (term == null) {
            return PostingList.empty("");
        }
        String normalized = term.toLowerCase(Locale.ROOT);
        DictionaryEntry entry = dictionary.get(normalized);
        if (entry == null) {
            return PostingList.empty(normalized);
        }
        return DiskIndexCodec.decodeDocIdPostingList(normalized, readPostingsSlice(entry),
                entry.getDocumentFrequency(), compressionMode);
    }

    @Override
    public final PostingCursor openCursor(String term, boolean positionsRequired) throws IOException {
        if (term == null) {
            return EmptyPostingCursor.INSTANCE;
        }
        String normalized = term.toLowerCase(Locale.ROOT);
        DictionaryEntry entry = dictionary.get(normalized);
        if (entry == null || entry.getDocumentFrequency() == 0) {
            return EmptyPostingCursor.INSTANCE;
        }
        if (compressionMode != IndexCompressionMode.DELTA_VARBYTE) {
            PostingList postingList = positionsRequired ? readPostingList(normalized) : readDocIdPostingList(normalized);
            return new ListPostingCursor(postingList);
        }
        ByteBuffer positions = positionsRequired ? readPositionsSlice(entry) : null;
        return new DiskPostingCursor(readPostingsSlice(entry), positions, entry.getDocumentFrequency());
    }

    @Override
    public final int getDocumentFrequency(String term) {
        if (term == null) {
            return 0;
        }
        DictionaryEntry entry = dictionary.get(term.toLowerCase(Locale.ROOT));
        return entry == null ? 0 : entry.getDocumentFrequency();
    }

    public final Map<String, DictionaryEntry> getDictionary() {
        return Collections.unmodifiableMap(dictionary);
    }

    public final Map<Integer, DocumentMeta> getDocuments() {
        return Collections.unmodifiableMap(documents);
    }

    public final DocumentMeta getDocumentMeta(int docId) {
        return documents.get(docId);
    }

    public final int getDocumentCount() {
        return documents.size();
    }

    protected abstract ByteBuffer readPostingsSlice(DictionaryEntry entry) throws IOException;

    protected abstract ByteBuffer readPositionsSlice(DictionaryEntry entry) throws IOException;
}
