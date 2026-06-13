package searchengine.storage;

import java.nio.ByteBuffer;

final class DiskPostingCursor implements PostingCursor {
    private static final int[] EMPTY_POSITIONS = new int[0];

    private final int documentFrequency;
    private final VarByteBufferCursor docIdGaps;
    private final VarByteBufferCursor termFrequencies;
    private final VarByteBufferCursor positionGaps;
    private final boolean positionsRequired;
    private final SkipEntry[] skips;

    private int read;
    private int previousDocId;
    private int docId;
    private int termFrequency;
    private int[] positions = EMPTY_POSITIONS;
    private boolean positioned;

    DiskPostingCursor(ByteBuffer postingsBuffer, ByteBuffer positionsBuffer, int documentFrequency) {
        this.documentFrequency = documentFrequency;
        ByteBuffer postings = postingsBuffer.slice();
        int docIdBytesLength = postings.getInt();
        ByteBuffer docIdBytes = slice(postings, docIdBytesLength);
        int tfBytesLength = postings.getInt();
        ByteBuffer tfBytes = slice(postings, tfBytesLength);
        ByteBuffer positionBytes = positionsBuffer == null ? null : positionsBuffer.slice();
        this.docIdGaps = new VarByteBufferCursor(docIdBytes.duplicate());
        this.termFrequencies = new VarByteBufferCursor(tfBytes.duplicate());
        this.positionsRequired = positionsBuffer != null;
        this.positionGaps = positionsRequired ? new VarByteBufferCursor(positionBytes.duplicate()) : null;
        if (docIdGaps.length() != documentFrequency || termFrequencies.length() != documentFrequency) {
            throw new IllegalStateException("Corrupted posting cursor");
        }
        this.skips = postings.hasRemaining()
                ? readStoredSkips(postings)
                : buildSkips(docIdBytes.duplicate(), tfBytes.duplicate(), positionBytes,
                        documentFrequency, positionsRequired);
    }

    @Override
    public boolean next() {
        if (read >= documentFrequency) {
            positioned = false;
            return false;
        }
        previousDocId += docIdGaps.next();
        docId = previousDocId;
        termFrequency = termFrequencies.next();
        positions = positionsRequired ? readPositions(termFrequency) : EMPTY_POSITIONS;
        read++;
        positioned = true;
        return true;
    }

    @Override
    public boolean isPositioned() {
        return positioned;
    }

    @Override
    public int docId() {
        requirePositioned();
        return docId;
    }

    @Override
    public int termFrequency() {
        requirePositioned();
        return termFrequency;
    }

    @Override
    public int[] positions() {
        requirePositioned();
        return positions;
    }

    @Override
    public boolean advanceTo(int targetDocId) {
        if (!positioned && !next()) {
            return false;
        }
        if (docId >= targetDocId) {
            return true;
        }
        SkipEntry skip = bestSkip(targetDocId);
        if (skip != null) {
            docIdGaps.setState(skip.docIdBytePosition, skip.index);
            termFrequencies.setState(skip.tfBytePosition, skip.index);
            if (positionsRequired) {
                positionGaps.setState(skip.positionBytePosition, skip.positionGapRead);
            }
            read = skip.index;
            previousDocId = skip.previousDocId;
            positioned = false;
            positions = EMPTY_POSITIONS;
        }
        while (!positioned || docId < targetDocId) {
            if (!next()) {
                return false;
            }
        }
        return true;
    }

    private int[] readPositions(int count) {
        int[] values = new int[count];
        int previous = 0;
        for (int i = 0; i < count; i++) {
            previous += positionGaps.next();
            values[i] = previous;
        }
        return values;
    }

    private SkipEntry bestSkip(int targetDocId) {
        int low = 0;
        int high = skips.length - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (skips[middle].targetDocId <= targetDocId) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (result < 0 || skips[result].index < read) {
            return null;
        }
        return skips[result];
    }

    private void requirePositioned() {
        if (!positioned) {
            throw new IllegalStateException("Cursor is not positioned");
        }
    }

    private static SkipEntry[] buildSkips(ByteBuffer docIdBytes, ByteBuffer tfBytes, ByteBuffer positionBytes,
                                          int documentFrequency, boolean positionsRequired) {
        if (documentFrequency < 4) {
            return new SkipEntry[0];
        }
        int skipStep = Math.max(1, (int) Math.sqrt(documentFrequency));
        SkipEntry[] entries = new SkipEntry[documentFrequency / skipStep];
        int size = 0;
        VarByteBufferCursor docs = new VarByteBufferCursor(docIdBytes);
        VarByteBufferCursor tfs = new VarByteBufferCursor(tfBytes);
        VarByteBufferCursor positions = positionsRequired ? new VarByteBufferCursor(positionBytes.duplicate()) : null;
        int previousDocId = 0;
        for (int index = 0; index < documentFrequency; index++) {
            int docIdBytePosition = docs.position();
            int tfBytePosition = tfs.position();
            int positionBytePosition = positionsRequired ? positions.position() : 0;
            int positionGapRead = positionsRequired ? positions.readCount() : 0;
            int previousBeforePosting = previousDocId;
            int currentDocId = previousDocId + docs.next();
            int tf = tfs.next();
            if (positionsRequired) {
                for (int i = 0; i < tf; i++) {
                    positions.next();
                }
            }
            if (index > 0 && index % skipStep == 0) {
                if (size == entries.length) {
                    SkipEntry[] next = new SkipEntry[entries.length * 2];
                    System.arraycopy(entries, 0, next, 0, entries.length);
                    entries = next;
                }
                entries[size++] = new SkipEntry(index, currentDocId, previousBeforePosting,
                        docIdBytePosition, tfBytePosition, positionBytePosition, positionGapRead);
            }
            previousDocId = currentDocId;
        }
        SkipEntry[] result = new SkipEntry[size];
        System.arraycopy(entries, 0, result, 0, size);
        return result;
    }

    private static SkipEntry[] readStoredSkips(ByteBuffer postings) {
        int count = postings.getInt();
        if (count < 0 || postings.remaining() != count * 7 * Integer.BYTES) {
            throw new IllegalStateException("Corrupted posting skip table");
        }
        SkipEntry[] entries = new SkipEntry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new SkipEntry(
                    postings.getInt(),
                    postings.getInt(),
                    postings.getInt(),
                    postings.getInt(),
                    postings.getInt(),
                    postings.getInt(),
                    postings.getInt()
            );
        }
        return entries;
    }

    private static ByteBuffer slice(ByteBuffer buffer, int length) {
        ByteBuffer slice = buffer.slice();
        slice.limit(length);
        buffer.position(buffer.position() + length);
        return slice;
    }

    private static final class SkipEntry {
        private final int index;
        private final int targetDocId;
        private final int previousDocId;
        private final int docIdBytePosition;
        private final int tfBytePosition;
        private final int positionBytePosition;
        private final int positionGapRead;

        private SkipEntry(int index, int targetDocId, int previousDocId, int docIdBytePosition,
                          int tfBytePosition, int positionBytePosition, int positionGapRead) {
            this.index = index;
            this.targetDocId = targetDocId;
            this.previousDocId = previousDocId;
            this.docIdBytePosition = docIdBytePosition;
            this.tfBytePosition = tfBytePosition;
            this.positionBytePosition = positionBytePosition;
            this.positionGapRead = positionGapRead;
        }
    }
}
