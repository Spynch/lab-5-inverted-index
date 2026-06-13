package searchengine.storage;

import searchengine.compression.IndexCompressionMode;
import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.PostingList;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;

public final class DiskIndexWriter {
    private final IndexCompressionMode compressionMode;

    public DiskIndexWriter() {
        this(IndexCompressionMode.DELTA_VARBYTE);
    }

    public DiskIndexWriter(IndexCompressionMode compressionMode) {
        this.compressionMode = Objects.requireNonNull(compressionMode, "compressionMode");
    }

    public DiskIndexStats write(InMemoryInvertedIndex index, Path directory) throws IOException {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(directory, "directory");
        Files.createDirectories(directory);

        List<DictionaryEntry> entries = new ArrayList<>();
        long postingsOffset = 0L;
        long positionsOffset = 0L;
        try (DataOutputStream postingsOut = output(DiskIndexFiles.postings(directory));
             DataOutputStream positionsOut = output(DiskIndexFiles.positions(directory))) {
            List<Map.Entry<String, PostingList>> sortedTerms = new ArrayList<>(index.getIndex().entrySet());
            sortedTerms.sort(Comparator.comparing(Map.Entry::getKey));
            for (Map.Entry<String, PostingList> termEntry : sortedTerms) {
                PostingList postingList = termEntry.getValue();
                byte[] postingsBytes = DiskIndexCodec.encodePostings(postingList, compressionMode);
                byte[] positionsBytes = DiskIndexCodec.encodePositions(postingList, compressionMode);
                postingsOut.write(postingsBytes);
                positionsOut.write(positionsBytes);
                entries.add(new DictionaryEntry(termEntry.getKey(), postingList.size(), postingsOffset, postingsBytes.length,
                        positionsOffset, positionsBytes.length));
                postingsOffset += postingsBytes.length;
                positionsOffset += positionsBytes.length;
            }
        }

        try (DataOutputStream dictionaryOut = output(DiskIndexFiles.dictionary(directory))) {
            DiskIndexCodec.writeDictionary(dictionaryOut, entries, compressionMode);
        }
        try (DataOutputStream documentsOut = output(DiskIndexFiles.documents(directory))) {
            DiskIndexCodec.writeDocuments(documentsOut, index.getDocuments());
        }

        return writeStableMeta(index, directory);
    }

    private static DataOutputStream output(Path path) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)));
    }

    private static DiskIndexStats stats(Path directory, long metaBytes) throws IOException {
        return new DiskIndexStats(
                Files.size(DiskIndexFiles.dictionary(directory)),
                Files.size(DiskIndexFiles.postings(directory)),
                Files.size(DiskIndexFiles.positions(directory)),
                Files.size(DiskIndexFiles.documents(directory)),
                metaBytes
        );
    }

    private DiskIndexStats writeStableMeta(InMemoryInvertedIndex index, Path directory) throws IOException {
        DiskIndexStats stats = stats(directory, 0L);
        for (int attempt = 0; attempt < 10; attempt++) {
            String metaJson = DiskIndexCodec.metaJson(index.getDocumentCount(), index.getIndex().size(),
                    index.getAvgDocumentLength(), stats, compressionMode);
            Files.write(DiskIndexFiles.meta(directory), metaJson.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            long metaBytes = Files.size(DiskIndexFiles.meta(directory));
            if (metaBytes == stats.getMetaBytes()) {
                return stats;
            }
            stats = stats(directory, metaBytes);
        }
        return stats;
    }
}
