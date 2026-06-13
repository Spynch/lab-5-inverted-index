package searchengine.storage;

import searchengine.compression.IndexCompressionMode;
import searchengine.document.DocumentMeta;
import searchengine.index.Posting;
import searchengine.index.PostingList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

final class DiskIndexCodec {
    static final int FORMAT_VERSION = 4;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int COMPRESSED_METADATA_FORMAT_VERSION = 2;
    private static final int SNIPPET_FORMAT_VERSION = 3;
    private static final int COMPRESSION_DEFLATE = 1;

    private DiskIndexCodec() {
    }

    static byte[] encodePostings(PostingList postingList, IndexCompressionMode compressionMode) throws IOException {
        int size = postingList.size();
        int[] docIds = new int[size];
        int[] termFrequencies = new int[size];
        for (int i = 0; i < size; i++) {
            Posting posting = postingList.getPostings().get(i);
            docIds[i] = posting.getDocId();
            termFrequencies[i] = posting.getTermFrequency();
        }
        byte[] docIdBytes = compressionMode.encodeDocIds(docIds);
        byte[] tfBytes = compressionMode.encodeValues(termFrequencies);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(docIdBytes.length);
            out.write(docIdBytes);
            out.writeInt(tfBytes.length);
            out.write(tfBytes);
            if (compressionMode == IndexCompressionMode.DELTA_VARBYTE) {
                writeSkipTable(out, postingList, docIds, termFrequencies);
            }
        }
        return bytes.toByteArray();
    }

    static byte[] encodePositions(PostingList postingList, IndexCompressionMode compressionMode) {
        int positionCount = 0;
        for (Posting posting : postingList.getPostings()) {
            positionCount += posting.getTermFrequency();
        }
        int[] values = new int[positionCount];
        int cursor = 0;
        for (Posting posting : postingList.getPostings()) {
            int previous = 0;
            for (int position : posting.getPositions()) {
                values[cursor++] = compressionMode.usesDeltaEncoding() ? position - previous : position;
                previous = position;
            }
        }
        return compressionMode.encodeValues(values);
    }

    static PostingList decodePostingList(String term, ByteBuffer postingsBuffer, ByteBuffer positionsBuffer,
                                         int documentFrequency, IndexCompressionMode compressionMode) {
        int docIdBytesLength = postingsBuffer.getInt();
        byte[] docIdBytes = new byte[docIdBytesLength];
        postingsBuffer.get(docIdBytes);
        int tfBytesLength = postingsBuffer.getInt();
        byte[] tfBytes = new byte[tfBytesLength];
        postingsBuffer.get(tfBytes);

        int[] docIds = compressionMode.decodeDocIds(docIdBytes);
        int[] termFrequencies = compressionMode.decodeValues(tfBytes);
        int[] positionValues = compressionMode.decodeValues(readRemaining(positionsBuffer));
        if (docIds.length != documentFrequency || termFrequencies.length != documentFrequency) {
            throw new IllegalStateException("Corrupted posting list for term: " + term);
        }

        List<Posting> postings = new ArrayList<>(documentFrequency);
        int positionCursor = 0;
        for (int i = 0; i < documentFrequency; i++) {
            int tf = termFrequencies[i];
            int[] positions = new int[tf];
            int previous = 0;
            for (int j = 0; j < tf; j++) {
                if (positionCursor >= positionValues.length) {
                    throw new IllegalStateException("Corrupted positions for term: " + term);
                }
                int encodedPosition = positionValues[positionCursor++];
                if (compressionMode.usesDeltaEncoding()) {
                    previous += encodedPosition;
                    positions[j] = previous;
                } else {
                    positions[j] = encodedPosition;
                }
            }
            postings.add(new Posting(docIds[i], tf, positions));
        }
        if (positionCursor != positionValues.length) {
            throw new IllegalStateException("Unused positions in posting list for term: " + term);
        }
        return new PostingList(term, postings);
    }

    static PostingList decodeDocIdPostingList(String term, ByteBuffer postingsBuffer, int documentFrequency,
                                              IndexCompressionMode compressionMode) {
        int docIdBytesLength = postingsBuffer.getInt();
        byte[] docIdBytes = new byte[docIdBytesLength];
        postingsBuffer.get(docIdBytes);
        int tfBytesLength = postingsBuffer.getInt();
        byte[] tfBytes = new byte[tfBytesLength];
        postingsBuffer.get(tfBytes);

        int[] docIds = compressionMode.decodeDocIds(docIdBytes);
        int[] termFrequencies = compressionMode.decodeValues(tfBytes);
        if (docIds.length != documentFrequency || termFrequencies.length != documentFrequency) {
            throw new IllegalStateException("Corrupted posting list for term: " + term);
        }

        List<Posting> postings = new ArrayList<>(documentFrequency);
        for (int i = 0; i < documentFrequency; i++) {
            postings.add(Posting.docOnly(docIds[i], termFrequencies[i]));
        }
        return new PostingList(term, postings);
    }

    static void writeDictionary(DataOutputStream out, List<DictionaryEntry> entries,
                                IndexCompressionMode compressionMode) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream payloadOut = new DataOutputStream(payload)) {
            payloadOut.writeInt(entries.size());
            for (DictionaryEntry entry : entries) {
                writeString(payloadOut, entry.getTerm());
                payloadOut.writeInt(entry.getDocumentFrequency());
                payloadOut.writeLong(entry.getPostingsOffset());
                payloadOut.writeInt(entry.getPostingsLength());
                payloadOut.writeLong(entry.getPositionsOffset());
                payloadOut.writeInt(entry.getPositionsLength());
            }
        }
        out.writeInt(FORMAT_VERSION);
        out.writeInt(compressionMode.ordinal());
        writeCompressedPayload(out, payload.toByteArray());
    }

    static DictionaryData readDictionary(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version == LEGACY_FORMAT_VERSION) {
            return new DictionaryData(readDictionaryPayload(in), IndexCompressionMode.DELTA_VARBYTE);
        }
        requireMetadataVersion(version);
        IndexCompressionMode compressionMode = version >= FORMAT_VERSION
                ? readCompressionMode(in)
                : IndexCompressionMode.DELTA_VARBYTE;
        Map<String, DictionaryEntry> entries = readDictionaryPayload(
                new DataInputStream(new ByteArrayInputStream(readCompressedPayload(in))));
        return new DictionaryData(entries, compressionMode);
    }

    private static Map<String, DictionaryEntry> readDictionaryPayload(DataInputStream in) throws IOException {
        int size = in.readInt();
        Map<String, DictionaryEntry> dictionary = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String term = readString(in);
            int documentFrequency = in.readInt();
            long postingsOffset = in.readLong();
            int postingsLength = in.readInt();
            long positionsOffset = in.readLong();
            int positionsLength = in.readInt();
            dictionary.put(term, new DictionaryEntry(term, documentFrequency, postingsOffset, postingsLength,
                    positionsOffset, positionsLength));
        }
        return dictionary;
    }

    static void writeDocuments(DataOutputStream out, Map<Integer, DocumentMeta> documents) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream payloadOut = new DataOutputStream(payload)) {
            payloadOut.writeInt(documents.size());
            for (DocumentMeta meta : documents.values()) {
                payloadOut.writeInt(meta.getDocId());
                writeString(payloadOut, meta.getExternalId());
                payloadOut.writeInt(meta.getLength());
                writeString(payloadOut, meta.getSnippet());
            }
        }
        out.writeInt(FORMAT_VERSION);
        writeCompressedPayload(out, payload.toByteArray());
    }

    static Map<Integer, DocumentMeta> readDocuments(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version == LEGACY_FORMAT_VERSION) {
            return readDocumentsPayload(in, false);
        }
        requireMetadataVersion(version);
        return readDocumentsPayload(new DataInputStream(new ByteArrayInputStream(readCompressedPayload(in))),
                version >= SNIPPET_FORMAT_VERSION);
    }

    private static Map<Integer, DocumentMeta> readDocumentsPayload(DataInputStream in, boolean hasSnippets) throws IOException {
        int size = in.readInt();
        Map<Integer, DocumentMeta> documents = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            int docId = in.readInt();
            String externalId = readString(in);
            int length = in.readInt();
            String snippet = hasSnippets ? readString(in) : "";
            documents.put(docId, new DocumentMeta(docId, externalId, length, snippet));
        }
        return documents;
    }

    static String metaJson(int documentCount, int termCount, double avgDocumentLength, DiskIndexStats stats,
                           IndexCompressionMode compressionMode) {
        String valueCodec = baseCodecName(compressionMode);
        return "{\n" +
                "  \"formatVersion\": " + FORMAT_VERSION + ",\n" +
                "  \"documentCount\": " + documentCount + ",\n" +
                "  \"termCount\": " + termCount + ",\n" +
                "  \"avgDocumentLength\": " + String.format(Locale.ROOT, "%.8f", avgDocumentLength) + ",\n" +
                "  \"docIdCompression\": \"" + compressionMode.getId() + "\",\n" +
                "  \"tfCompression\": \"" + valueCodec + "\",\n" +
                "  \"positionCompression\": \"" +
                (compressionMode.usesDeltaEncoding() ? "delta-" : "") + valueCodec + "\",\n" +
                "  \"dictionaryCompression\": \"deflate\",\n" +
                "  \"documentCompression\": \"deflate\",\n" +
                "  \"dictionaryBytes\": " + stats.getDictionaryBytes() + ",\n" +
                "  \"postingsBytes\": " + stats.getPostingsBytes() + ",\n" +
                "  \"positionsBytes\": " + stats.getPositionsBytes() + ",\n" +
                "  \"documentsBytes\": " + stats.getDocumentsBytes() + ",\n" +
                "  \"totalBytes\": " + stats.getTotalBytes() + "\n" +
                "}\n";
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireMetadataVersion(int version) {
        if (version != COMPRESSED_METADATA_FORMAT_VERSION && version != SNIPPET_FORMAT_VERSION
                && version != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported disk index format version: " + version);
        }
    }

    private static IndexCompressionMode readCompressionMode(DataInputStream in) throws IOException {
        int ordinal = in.readInt();
        IndexCompressionMode[] modes = IndexCompressionMode.values();
        if (ordinal < 0 || ordinal >= modes.length) {
            throw new IllegalStateException("Unsupported index compression mode: " + ordinal);
        }
        return modes[ordinal];
    }

    private static String baseCodecName(IndexCompressionMode compressionMode) {
        switch (compressionMode) {
            case NONE:
            case DELTA:
                return "none";
            case VARBYTE:
            case DELTA_VARBYTE:
                return "varbyte";
            case BITPACKING:
            case DELTA_BITPACKING:
                return "bitpacking";
            case PFOR:
            case DELTA_PFOR:
                return "pfor";
            default:
                throw new IllegalArgumentException("Unsupported compression mode: " + compressionMode);
        }
    }

    private static void writeCompressedPayload(DataOutputStream out, byte[] payload) throws IOException {
        byte[] compressed = deflate(payload);
        out.writeInt(COMPRESSION_DEFLATE);
        out.writeInt(payload.length);
        out.writeInt(compressed.length);
        out.write(compressed);
    }

    private static byte[] readCompressedPayload(DataInputStream in) throws IOException {
        int compression = in.readInt();
        if (compression != COMPRESSION_DEFLATE) {
            throw new IllegalStateException("Unsupported block compression: " + compression);
        }
        int uncompressedLength = in.readInt();
        int compressedLength = in.readInt();
        byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);
        byte[] payload = inflate(compressed);
        if (payload.length != uncompressedLength) {
            throw new IllegalStateException("Corrupted compressed payload");
        }
        return payload;
    }

    private static byte[] deflate(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes)) {
            deflater.write(payload);
        }
        return bytes.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return inflater.readAllBytes();
        }
    }

    private static byte[] readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static void writeSkipTable(DataOutputStream out, PostingList postingList, int[] docIds,
                                       int[] termFrequencies) throws IOException {
        int documentFrequency = docIds.length;
        if (documentFrequency < 4) {
            out.writeInt(0);
            return;
        }
        int skipStep = Math.max(1, (int) Math.sqrt(documentFrequency));
        int skipCount = (documentFrequency - 1) / skipStep;
        out.writeInt(skipCount);

        int docBytePosition = varIntSize(documentFrequency);
        int tfBytePosition = varIntSize(documentFrequency);
        int totalPositions = 0;
        for (int tf : termFrequencies) {
            totalPositions += tf;
        }
        int positionBytePosition = varIntSize(totalPositions);
        int positionGapRead = 0;
        int previousDocId = 0;
        for (int index = 0; index < documentFrequency; index++) {
            if (index > 0 && index % skipStep == 0) {
                out.writeInt(index);
                out.writeInt(docIds[index]);
                out.writeInt(previousDocId);
                out.writeInt(docBytePosition);
                out.writeInt(tfBytePosition);
                out.writeInt(positionBytePosition);
                out.writeInt(positionGapRead);
            }

            docBytePosition += varIntSize(docIds[index] - previousDocId);
            tfBytePosition += varIntSize(termFrequencies[index]);
            int previousPosition = 0;
            for (int position : postingList.get(index).getPositions()) {
                positionBytePosition += varIntSize(position - previousPosition);
                previousPosition = position;
                positionGapRead++;
            }
            previousDocId = docIds[index];
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }
}
