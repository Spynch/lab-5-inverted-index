package searchengine.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class TxtFolderDocumentLoader implements DocumentLoader {
    private final Path folder;

    public TxtFolderDocumentLoader(Path folder) {
        this.folder = Objects.requireNonNull(folder, "folder");
    }

    @Override
    public Iterator<Document> load() {
        try {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(folder)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".txt"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(files::add);
            }
            List<Document> documents = new ArrayList<>(files.size());
            int docId = 1;
            for (Path file : files) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                documents.add(new Document(docId++, folder.relativize(file).toString(), text));
            }
            return documents.iterator();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
