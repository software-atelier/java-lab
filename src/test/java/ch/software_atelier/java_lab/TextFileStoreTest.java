package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class TextFileStoreTest {
    @TempDir Path directory;
    @Test void utf8TextRoundTripsWithBomLineEndingsAndOptimisticConflictDetection() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), directory);var text = new TextFileStore(files);
        Files.writeString(directory.resolve("sample.json"), "\ufeff{\r\n  \"id\": \"001\"\r\n}\r\n");
        var loaded = text.read("sample.json");
        assertThat(loaded.content()).doesNotContain("\r").contains("001");assertThat(loaded.bom()).isTrue();assertThat(loaded.recordSeparator()).isEqualTo("\r\n");
        var saved = text.save(loaded.path(), "{\n  \"name\": \"Zürich\"\n}\n", loaded.bom(), loaded.recordSeparator(), loaded.revision());
        assertThat(Files.readString(directory.resolve("sample.json"))).startsWith("\ufeff").contains("\r\n", "Zürich");
        assertThatThrownBy(() -> text.save(loaded.path(), loaded.content(), false, "\n", loaded.revision())).hasMessageContaining("changed on disk");
        assertThat(text.read(loaded.path()).revision()).isEqualTo(saved.revision());
        // Draft syntax is allowed; validation/formatting is an explicit editor operation.
        text.save(saved.path(), "{ unfinished", saved.bom(), saved.recordSeparator(), saved.revision());
        assertThat(text.read(saved.path()).content()).isEqualTo("{ unfinished");
    }
    @Test void rejectsUnsafePathsUnsupportedTypesInvalidEncodingAndOversizedFiles() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), directory);var text = new TextFileStore(files);
        Files.writeString(directory.resolve("notes.txt"), "hello");
        Files.createSymbolicLink(directory.resolve("alias.txt"), directory.resolve("notes.txt"));
        for (String path : java.util.List.of("../notes.txt", "/notes.txt", "alias.txt", ".private.txt", "notebook.jnb", "binary.bin"))
            assertThatThrownBy(() -> text.read(path)).as(path).isInstanceOf(ResponseStatusException.class);
        Files.write(directory.resolve("invalid.txt"), new byte[]{(byte)0xc3, 0x28});
        assertThatThrownBy(() -> text.read("invalid.txt")).hasMessageContaining("UTF-8");
        Files.writeString(directory.resolve("big.txt"), "x".repeat(TextFileStore.MAX_BYTES+1));
        assertThatThrownBy(() -> text.read("big.txt")).hasMessageContaining("2 MB");
    }
    @Test void listsAllVisibleRegularFilesWithAppropriateEditorsAndSafeDownloadHeaders() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), directory);
        files.create("Notebook");Files.writeString(directory.resolve("legacy.ipynb"), "{}");
        for(String name : java.util.List.of("data.json","data.xml","notes.txt","image.png","README.md"))Files.writeString(directory.resolve(name), "content");
        Files.writeString(directory.resolve(".hidden.txt"), "hidden");Files.createSymbolicLink(directory.resolve("alias.json"), directory.resolve("data.json"));
        assertThat(files.list()).extracting(NotebookStore.Entry::name).contains("data.json","data.xml","notes.txt","image.png","README.md","legacy.ipynb").doesNotContain(".hidden.txt","alias.json");
        assertThat(files.list()).filteredOn(e -> e.name().equals("data.xml")).extracting(NotebookStore.Entry::type).containsExactly("text");
        var download = new FileController(new TextFileStore(files), files).download("image.png");
        assertThat(download.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(download.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
    }
}
