package searchengine.compression;

import searchengine.index.InMemoryInvertedIndex;
import searchengine.index.Posting;
import searchengine.index.PostingList;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CompressionAnalyzer {
    private CompressionAnalyzer() {
    }

    public static List<Result> analyze(InMemoryInvertedIndex index) {
        List<Result> results = new ArrayList<>();
        for (IndexCompressionMode mode : IndexCompressionMode.values()) {
            long rawBytes = 0L;
            long compressedBytes = 0L;
            long encodeNanos = 0L;
            long decodeNanos = 0L;
            for (PostingList postingList : index.getIndex().values()) {
                int[] docIds = new int[postingList.size()];
                int[] termFrequencies = new int[postingList.size()];
                int positionCount = 0;
                for (int i = 0; i < postingList.size(); i++) {
                    Posting posting = postingList.get(i);
                    docIds[i] = posting.getDocId();
                    termFrequencies[i] = posting.getTermFrequency();
                    positionCount += posting.getTermFrequency();
                }
                int[] positions = positionValues(postingList, mode.usesDeltaEncoding(), positionCount);
                rawBytes += (long) (docIds.length + termFrequencies.length + positionCount) * Integer.BYTES;

                long started = System.nanoTime();
                byte[] encodedDocs = mode.encodeDocIds(docIds);
                byte[] encodedTfs = mode.encodeValues(termFrequencies);
                byte[] encodedPositions = mode.encodeValues(positions);
                encodeNanos += System.nanoTime() - started;
                compressedBytes += encodedDocs.length + encodedTfs.length + encodedPositions.length;

                started = System.nanoTime();
                int[] decodedDocs = mode.decodeDocIds(encodedDocs);
                int[] decodedTfs = mode.decodeValues(encodedTfs);
                int[] decodedPositions = mode.decodeValues(encodedPositions);
                decodeNanos += System.nanoTime() - started;
                if (!Arrays.equals(docIds, decodedDocs)
                        || !Arrays.equals(termFrequencies, decodedTfs)
                        || !Arrays.equals(positions, decodedPositions)) {
                    throw new IllegalStateException("Compression round-trip failed for " + mode.getId()
                            + " and term " + postingList.getTerm());
                }
            }
            results.add(new Result(mode, rawBytes, compressedBytes, encodeNanos, decodeNanos));
        }
        return results;
    }

    public static void printReport(InMemoryInvertedIndex index, PrintStream out) {
        List<Result> results = analyze(index);
        out.println("Compression comparison for posting payloads:");
        out.println("mode, raw_bytes, compressed_bytes, ratio, encode_ms, decode_ms");
        for (Result result : results) {
            out.printf(Locale.ROOT, "%s, %d, %d, %.3f, %.3f, %.3f%n",
                    result.getMode().getId(), result.getRawBytes(), result.getCompressedBytes(),
                    result.getCompressionRatio(), result.getEncodeNanos() / 1_000_000.0,
                    result.getDecodeNanos() / 1_000_000.0);
        }
        printDiagnostic(index, out);
    }

    private static void printDiagnostic(InMemoryInvertedIndex index, PrintStream out) {
        PostingList sample = index.getIndex().values().stream()
                .max(Comparator.comparingInt(PostingList::size))
                .orElse(null);
        if (sample == null) {
            return;
        }
        int limit = Math.min(16, sample.size());
        int[] docIds = new int[limit];
        int[] deltas = new int[limit];
        int[] termFrequencies = new int[limit];
        int previous = 0;
        for (int i = 0; i < limit; i++) {
            Posting posting = sample.get(i);
            docIds[i] = posting.getDocId();
            deltas[i] = docIds[i] - previous;
            termFrequencies[i] = posting.getTermFrequency();
            previous = docIds[i];
        }
        out.println("Diagnostic sample term: " + sample.getTerm() + ", df=" + sample.size());
        out.println("docIds=" + Arrays.toString(docIds));
        out.println("docIdDeltas=" + Arrays.toString(deltas));
        out.println("termFrequencies=" + Arrays.toString(termFrequencies));
        Posting samplePosting = sample.get(0);
        int[] samplePositions = Arrays.copyOf(samplePosting.getPositions(),
                Math.min(16, samplePosting.getTermFrequency()));
        int[] samplePositionDeltas = deltas(samplePositions);
        out.println("positions(docId=" + samplePosting.getDocId() + ")=" + Arrays.toString(samplePositions));
        out.println("positionDeltas=" + Arrays.toString(samplePositionDeltas));
        for (IndexCompressionMode mode : IndexCompressionMode.values()) {
            int[] encodedPositions = mode.usesDeltaEncoding() ? samplePositionDeltas : samplePositions;
            out.println(mode.getId() + " sample_docid_bytes=" + mode.encodeDocIds(docIds).length
                    + " sample_tf_bytes=" + mode.encodeValues(termFrequencies).length
                    + " sample_position_bytes=" + mode.encodeValues(encodedPositions).length);
        }
    }

    private static int[] deltas(int[] values) {
        int[] deltas = new int[values.length];
        int previous = 0;
        for (int i = 0; i < values.length; i++) {
            deltas[i] = values[i] - previous;
            previous = values[i];
        }
        return deltas;
    }

    private static int[] positionValues(PostingList postingList, boolean gaps, int positionCount) {
        int[] values = new int[positionCount];
        int cursor = 0;
        for (Posting posting : postingList.getPostings()) {
            int previous = 0;
            for (int position : posting.getPositions()) {
                values[cursor++] = gaps ? position - previous : position;
                previous = position;
            }
        }
        return values;
    }

    public static final class Result {
        private final IndexCompressionMode mode;
        private final long rawBytes;
        private final long compressedBytes;
        private final long encodeNanos;
        private final long decodeNanos;

        Result(IndexCompressionMode mode, long rawBytes, long compressedBytes,
               long encodeNanos, long decodeNanos) {
            this.mode = mode;
            this.rawBytes = rawBytes;
            this.compressedBytes = compressedBytes;
            this.encodeNanos = encodeNanos;
            this.decodeNanos = decodeNanos;
        }

        public IndexCompressionMode getMode() {
            return mode;
        }

        public long getRawBytes() {
            return rawBytes;
        }

        public long getCompressedBytes() {
            return compressedBytes;
        }

        public long getEncodeNanos() {
            return encodeNanos;
        }

        public long getDecodeNanos() {
            return decodeNanos;
        }

        public double getCompressionRatio() {
            return compressedBytes == 0L ? 0.0 : (double) rawBytes / compressedBytes;
        }
    }
}
