package searchengine.storage;

import searchengine.index.PostingList;

import java.io.IOException;

public interface PostingListReader extends AutoCloseable {
    PostingList readPostingList(String term) throws IOException;

    default PostingList readDocIdPostingList(String term) throws IOException {
        return readPostingList(term);
    }

    default PostingCursor openCursor(String term, boolean positionsRequired) throws IOException {
        PostingList postingList = positionsRequired ? readPostingList(term) : readDocIdPostingList(term);
        return postingList.isEmpty() ? EmptyPostingCursor.INSTANCE : new ListPostingCursor(postingList);
    }

    default int getDocumentFrequency(String term) throws IOException {
        return readDocIdPostingList(term).size();
    }

    @Override
    void close() throws IOException;
}
