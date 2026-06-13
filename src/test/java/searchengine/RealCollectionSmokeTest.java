package searchengine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import searchengine.index.Posting;
import searchengine.index.PostingList;
import searchengine.storage.DiskSearchEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealCollectionSmokeTest {
    @Test
    void validatesRussianControlQueriesAgainstConfiguredIndex() throws Exception {
        String configuredPath = System.getProperty("real.index.dir");
        Assumptions.assumeTrue(configuredPath != null && Files.isDirectory(Path.of(configuredPath)),
                "Run with -Dreal.index.dir=<built-russian-index>");

        try (DiskSearchEngine engine = DiskSearchEngine.mmap(Path.of(configuredPath))) {
            PostingList both = engine.execute("история AND россия");
            PostingList adjacent = engine.execute("история ADJ россия");
            PostingList near = engine.execute("история NEAR/1 россия");
            PostingList historyWithoutAdjacent = engine.execute("история AND NOT (история ADJ россия)");
            PostingList nonAdjacent = engine.execute("история AND россия AND NOT (история ADJ россия)");

            assertFalse(both.isEmpty(), "The configured collection must contain история AND россия");
            assertSubset(adjacent, both);
            assertSubset(near, both);
            assertFalse(historyWithoutAdjacent.isEmpty(),
                    "The configured collection must contain history documents without the exact phrase");
            assertSubset(nonAdjacent, both);
            validateAdjacentPositions(engine, adjacent, "история", "россия");
            validateNearPositions(engine, near, "история", "россия", 1);
            validateHistoryWithoutAdjacent(engine, historyWithoutAdjacent, "история", "россия");
            validateNotAdjacent(engine, nonAdjacent, "история", "россия");
        }
    }

    private static void validateAdjacentPositions(DiskSearchEngine engine, PostingList matches,
                                                  String left, String right) throws Exception {
        for (Posting posting : matches.getPostings()) {
            int[] leftPositions = positions(engine, left, posting.getDocId());
            int[] rightPositions = positions(engine, right, posting.getDocId());
            assertTrue(hasOrderedDistance(leftPositions, rightPositions, 1));
        }
    }

    private static void validateNearPositions(DiskSearchEngine engine, PostingList matches,
                                              String left, String right, int distance) throws Exception {
        for (Posting posting : matches.getPostings()) {
            int[] leftPositions = positions(engine, left, posting.getDocId());
            int[] rightPositions = positions(engine, right, posting.getDocId());
            assertTrue(hasNearDistance(leftPositions, rightPositions, distance));
        }
    }

    private static void validateNotAdjacent(DiskSearchEngine engine, PostingList matches,
                                            String left, String right) throws Exception {
        for (Posting posting : matches.getPostings()) {
            int[] leftPositions = positions(engine, left, posting.getDocId());
            int[] rightPositions = positions(engine, right, posting.getDocId());
            assertFalse(hasOrderedDistance(leftPositions, rightPositions, 1));
        }
    }

    private static void validateHistoryWithoutAdjacent(DiskSearchEngine engine, PostingList matches,
                                                       String left, String right) throws Exception {
        for (Posting posting : matches.getPostings()) {
            int[] leftPositions = positions(engine, left, posting.getDocId());
            int[] rightPositions = engine.termPositions(right, posting.getDocId());
            assertFalse(hasOrderedDistance(leftPositions, rightPositions, 1));
        }
    }

    private static int[] positions(DiskSearchEngine engine, String term, int docId) throws Exception {
        int[] positions = engine.termPositions(term, docId);
        assertTrue(positions.length > 0, "Missing term " + term + " in doc " + docId);
        return positions;
    }

    private static boolean hasOrderedDistance(int[] left, int[] right, int distance) {
        for (int leftPosition : left) {
            for (int rightPosition : right) {
                if (rightPosition - leftPosition == distance) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNearDistance(int[] left, int[] right, int distance) {
        for (int leftPosition : left) {
            for (int rightPosition : right) {
                if (Math.abs(rightPosition - leftPosition) <= distance) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertSubset(PostingList subset, PostingList superset) {
        for (Posting posting : subset.getPostings()) {
            assertTrue(superset.getPostings().stream()
                    .anyMatch(candidate -> candidate.getDocId() == posting.getDocId()));
        }
    }
}
