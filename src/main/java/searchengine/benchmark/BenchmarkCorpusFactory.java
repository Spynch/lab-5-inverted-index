package searchengine.benchmark;

import searchengine.document.Document;
import searchengine.index.InMemoryInvertedIndex;

import java.util.ArrayList;
import java.util.List;

final class BenchmarkCorpusFactory {
    private BenchmarkCorpusFactory() {
    }

    static InMemoryInvertedIndex buildIndex(int documentCount, int termsPerDocument) {
        InMemoryInvertedIndex index = new InMemoryInvertedIndex();
        index.build(buildDocuments(documentCount, termsPerDocument));
        return index;
    }

    private static List<Document> buildDocuments(int documentCount, int termsPerDocument) {
        List<Document> documents = new ArrayList<>(documentCount);
        for (int docId = 1; docId <= documentCount; docId++) {
            StringBuilder text = new StringBuilder(termsPerDocument * 8);
            for (int position = 0; position < termsPerDocument; position++) {
                text.append(term(docId, position)).append(' ');
            }
            documents.add(new Document(docId, "doc-" + docId, text.toString()));
        }
        return documents;
    }

    private static String term(int docId, int position) {
        if (position % 5 == 0) {
            return "common";
        }
        if (position % 17 == 0) {
            return "medium" + (docId % 32);
        }
        return "term" + ((docId * 31 + position * 17) % 4096);
    }
}