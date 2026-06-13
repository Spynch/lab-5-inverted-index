package searchengine.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class PostingList {
    private final String term;
    private final List<Posting> postings;
    private final List<SkipPointer> skipPointers;
    private final SkipPointer[] skipBySourceIndex;

    public PostingList(String term, List<Posting> postings) {
        this(term, postings, false);
    }

    private PostingList(String term, List<Posting> postings, boolean alreadySortedAndOwned) {
        this.term = Objects.requireNonNull(term, "term");
        Objects.requireNonNull(postings, "postings");
        List<Posting> sorted;
        if (alreadySortedAndOwned) {
            sorted = postings;
        } else {
            sorted = new ArrayList<>(postings);
            sorted.sort(Comparator.comparingInt(Posting::getDocId));
        }
        this.postings = Collections.unmodifiableList(sorted);
        this.skipPointers = Collections.unmodifiableList(buildSkipPointers(sorted));
        this.skipBySourceIndex = new SkipPointer[sorted.size()];
        for (SkipPointer skipPointer : skipPointers) {
            skipBySourceIndex[skipPointer.getFromIndex()] = skipPointer;
        }
    }

    public static PostingList empty(String term) {
        return trustedSorted(term, Collections.emptyList());
    }

    static PostingList trustedSorted(String term, List<Posting> postings) {
        return new PostingList(term, postings, true);
    }

    public String getTerm() {
        return term;
    }

    public List<Posting> getPostings() {
        return postings;
    }

    public List<SkipPointer> getSkipPointers() {
        return skipPointers;
    }

    public int size() {
        return postings.size();
    }

    public boolean isEmpty() {
        return postings.isEmpty();
    }

    public Posting get(int index) {
        return postings.get(index);
    }

    SkipPointer skipFrom(int index) {
        if (index < 0 || index >= skipBySourceIndex.length) {
            return null;
        }
        return skipBySourceIndex[index];
    }

    private static List<SkipPointer> buildSkipPointers(List<Posting> postings) {
        int size = postings.size();
        if (size < 4) {
            return Collections.emptyList();
        }
        int skipStep = Math.max(1, (int) Math.sqrt(size));
        List<SkipPointer> pointers = new ArrayList<>();
        for (int from = 0; from + skipStep < size; from += skipStep) {
            int to = from + skipStep;
            pointers.add(new SkipPointer(from, to, postings.get(to).getDocId()));
        }
        return pointers;
    }
}
