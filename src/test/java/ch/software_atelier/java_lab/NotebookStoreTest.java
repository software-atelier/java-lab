package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class NotebookStoreTest {
    @TempDir Path directory;

    @Test void notebooksRoundTripWithoutLosingMetadataAndNamesDoNotCollide() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        String name = store.create("Experiment");
        assertThat(name).isEqualTo("Experiment.jnb");
        assertThat(store.create("Experiment")).isEqualTo("Experiment-1.jnb");
        var notebook = store.read(name);
        notebook.withObject("metadata").put("custom", "keep me");
        ((com.fasterxml.jackson.databind.node.ObjectNode) notebook.path("cells").get(0)).put("source", "int answer = 42;");
        store.save(name, notebook);
        assertThat(store.read(name)).isEqualTo(notebook);
        assertThat(Files.readString(directory.resolve(name))).contains("keep me", "int answer = 42;");
        assertThat(store.list()).hasSize(2);
        String renamed = store.rename(name, "Renamed notebook");
        assertThat(store.read(renamed)).isEqualTo(notebook);
        assertThat(Files.exists(directory.resolve(name))).isFalse();
        store.delete(renamed);
        assertThat(store.list()).hasSize(1);
        try (var files = Files.list(directory)) { assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp")); }
    }

    @Test void rejectsTraversalSymlinksConflictingRenamesAndInvalidDocuments() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        assertThatThrownBy(() -> store.create("../escape.ipynb")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.read("nested/file.ipynb")).isInstanceOf(ResponseStatusException.class);
        String first = store.create("one");
        store.create("two");
        assertThatThrownBy(() -> store.rename(first, "two.jnb")).isInstanceOf(ResponseStatusException.class);
        Path link = directory.resolve("linked.ipynb");
        Files.createSymbolicLink(link, directory.resolve(first));
        assertThatThrownBy(() -> store.read("linked.ipynb")).isInstanceOf(ResponseStatusException.class);
        assertThat(store.list()).extracting(NotebookStore.Entry::name).doesNotContain("linked.ipynb");
        var before = store.read(first);
        assertThatThrownBy(() -> store.save(first, new ObjectMapper().createObjectNode())).isInstanceOf(ResponseStatusException.class);
        assertThat(store.read(first)).isEqualTo(before);
    }

    @Test void legacyNotebooksRemainReadableAndCanBeRenamedToTheNativeExtension() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        var original = store.read(store.create("Original"));
        store.save("Legacy.ipynb", original);
        assertThat(store.list()).extracting(NotebookStore.Entry::name).containsExactly("Legacy.ipynb", "Original.jnb");
        assertThat(store.read("Legacy.ipynb")).isEqualTo(original);
        assertThat(store.rename("Legacy.ipynb", "Legacy.ipynb")).isEqualTo("Legacy.jnb");
        assertThat(store.read("Legacy.jnb")).isEqualTo(original);
        assertThat(store.list()).extracting(NotebookStore.Entry::name).contains("Legacy.jnb");
        assertThat(Files.exists(directory.resolve("Legacy.ipynb"))).isFalse();
    }

    @Test void nestedFoldersKeepNotebooksAndUploadsContained() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        assertThat(store.createFolder("Projects")).isEqualTo("Projects");
        assertThat(store.createFolder("Projects/2026")).isEqualTo("Projects/2026");
        assertThat(store.folders("Projects")).extracting(NotebookStore.Folder::name).containsExactly("2026");
        String notebook = store.create("Projects/2026/Analysis");
        assertThat(notebook).isEqualTo("Projects/2026/Analysis.jnb");
        assertThat(store.list("Projects/2026")).extracting(NotebookStore.Entry::name).containsExactly("Analysis.jnb");
        assertThat(store.read(notebook)).isNotNull();
        var upload = store.upload("Projects/2026", "data.csv", "name,age\nAda,28\nBob,31\n".getBytes());
        assertThat(upload.path()).isEqualTo("Projects/2026/data.csv");
        assertThat(upload.preview()).containsExactly(
            java.util.List.of("name", "age"), java.util.List.of("Ada", "28"), java.util.List.of("Bob", "31"));
        assertThat(store.upload("Projects/2026", "data.csv", "a,b\n".getBytes()).name()).isEqualTo("data-1.csv");
        assertThatThrownBy(() -> store.deleteFolder("Projects/2026")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.createFolder("../escape")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.list("Projects/../2026")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.upload("Projects/2026", "../escape.csv", new byte[]{1}))
            .isInstanceOf(ResponseStatusException.class);
        for (String unsafe : java.util.List.of("/tmp/outside.csv", "sub/data.csv", "..\\escape.csv", "C:\\outside.csv", "COPY.JNB", "LEGACY.IPYNB"))
            assertThatThrownBy(() -> store.upload("Projects/2026", unsafe, new byte[]{1}))
                .as("unsafe upload filename: %s", unsafe).isInstanceOf(ResponseStatusException.class);
        Files.createSymbolicLink(directory.resolve("link"), directory.resolve("Projects"));
        assertThatThrownBy(() -> store.list("link")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void movesNotebooksBetweenFoldersWithoutOverwritingAndKeepsLocalValues() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        store.createFolder("From");
        store.createFolder("To");
        String original = store.create("From/Report");
        var notebook = store.read(original);
        var variables = notebook.withObject("metadata").withObject("java_lab").putArray("variables");
        variables.addObject().put("name", "TOKEN").put("defaultValue", "shareable");
        store.save(original, notebook);
        store.updateValues(original, java.util.Map.of("TOKEN", "private"), java.util.List.of());

        assertThat(store.move(original, "To")).isEqualTo("To/Report.jnb");
        assertThat(store.list("From")).isEmpty();
        assertThat(store.read("To/Report.jnb")).isEqualTo(notebook);
        assertThat(store.effectiveValues("To/Report.jnb")).containsEntry("TOKEN", "private");
        assertThat(store.move("To/Report.jnb", "To")).isEqualTo("To/Report.jnb");

        store.create("From/Report");
        assertThatThrownBy(() -> store.move("To/Report.jnb", "From"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(store.read("To/Report.jnb")).isEqualTo(notebook);
    }

    @Test void rejectsUnsafeNotebookMoves() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        store.createFolder("Safe");
        store.create("Safe/Notebook");
        Files.writeString(directory.resolve(".private.txt"), "secret");
        for (String source : java.util.List.of(".private.txt", "../private.jnb", "Safe/../Notebook.jnb"))
            assertThatThrownBy(() -> store.move(source, ""))
                .as("source: %s", source).isInstanceOf(ResponseStatusException.class);
        for (String target : java.util.List.of("../Outside", "Missing", "Safe/.."))
            assertThatThrownBy(() -> store.move("Safe/Notebook.jnb", target))
                .as("target: %s", target).isInstanceOf(ResponseStatusException.class);
        Files.createSymbolicLink(directory.resolve("Linked"), directory.resolve("Safe"));
        assertThatThrownBy(() -> store.move("Safe/Notebook.jnb", "Linked"))
            .isInstanceOf(ResponseStatusException.class);
        Files.createSymbolicLink(directory.resolve("Safe/alias.jnb"), directory.resolve("Safe/Notebook.jnb"));
        assertThatThrownBy(() -> store.move("Safe/alias.jnb", ""))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(Files.exists(directory.resolve("Safe/Notebook.jnb"))).isTrue();
    }

    @Test void listsNativeNotebooksAndCsvAndMovesUploadedDataFiles() throws Exception {
        var store = new NotebookStore(new ObjectMapper(), directory);
        store.createFolder("Input");
        store.createFolder("Output");
        store.create("Input/Analysis");
        store.upload("Input", "data.csv", "name\nAda\n".getBytes());
        store.save("Input/Legacy.ipynb", store.read("Input/Analysis.jnb"));
        Files.writeString(directory.resolve("Input/notes.txt"), "notes");
        Files.writeString(directory.resolve("Input/.hidden.txt"), "hidden");
        Files.writeString(directory.resolve("Input/.hidden.jnb"), "hidden");
        Files.createDirectory(directory.resolve(".internal"));
        Files.createSymbolicLink(directory.resolve("Input/linked.csv"), directory.resolve("Input/data.csv"));

        assertThat(store.list("Input")).extracting(NotebookStore.Entry::name, NotebookStore.Entry::type)
            .containsExactly(tuple("Analysis.jnb", "notebook"), tuple("data.csv", "csv"), tuple("Legacy.ipynb", "notebook"), tuple("notes.txt", "text"));
        assertThat(store.folders("")).extracting(NotebookStore.Folder::name).doesNotContain(".internal");
        assertThatThrownBy(() -> store.move("Input/data.csv", ".internal"))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(store.move("Input/data.csv", "Output")).isEqualTo("Output/data.csv");
        assertThat(Files.readString(directory.resolve("Output/data.csv"))).isEqualTo("name\nAda\n");
        assertThat(store.list("Output")).extracting(NotebookStore.Entry::name, NotebookStore.Entry::type).containsExactly(tuple("data.csv", "csv"));
        assertThat(store.list("Input")).extracting(NotebookStore.Entry::name).containsExactly("Analysis.jnb", "Legacy.ipynb", "notes.txt");
        assertThatThrownBy(() -> store.move("Input/.hidden.txt", "Output"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.move("Input/linked.csv", "Output"))
            .isInstanceOf(ResponseStatusException.class);
    }
}
