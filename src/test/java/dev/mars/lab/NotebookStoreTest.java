package dev.mars.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

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
        assertThat(store.list()).extracting(NotebookStore.Entry::name).contains("Legacy.ipynb", "Original.jnb");
        assertThat(store.read("Legacy.ipynb")).isEqualTo(original);
        assertThat(store.rename("Legacy.ipynb", "Legacy.ipynb")).isEqualTo("Legacy.jnb");
        assertThat(store.read("Legacy.jnb")).isEqualTo(original);
        assertThat(Files.exists(directory.resolve("Legacy.ipynb"))).isFalse();
    }
}
