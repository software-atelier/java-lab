package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DataDirectoryTest {
    @TempDir Path directory;
    @Test void resolvesDefaultRelativeAndAbsoluteDataPathsAndCreatesTheDirectory() throws Exception {
        assertThat(NotebookStore.dataDirectory(directory, "")).isEqualTo(directory.resolve("data").toAbsolutePath());
        assertThat(NotebookStore.dataDirectory(directory, "nested/files")).isEqualTo(directory.resolve("nested/files").toAbsolutePath());
        assertThat(NotebookStore.dataDirectory(directory, directory.resolve("absolute").toString())).isEqualTo(directory.resolve("absolute").toAbsolutePath());
        var store = new NotebookStore(new ObjectMapper(), directory.resolve("nested/new-data"));
        assertThat(Files.isDirectory(Path.of(store.directory()))).isTrue();
        assertThat(store.list()).isEmpty();
    }
    @Test void relativeJavaAndCsvPathsUseTheConfiguredDataDirectoryIncludingSpaces() throws Exception {
        Path data = directory.resolve("data with spaces");
        var store = new NotebookStore(new ObjectMapper(), data);
        Files.writeString(data.resolve("input.csv"), "id,value\n001,42\n");
        try (var kernels = new KernelService(store)) {
            String id = kernels.create();
            var result = kernels.execute(id, "var table = Csv.load(\"input.csv\"); Csv.save(\"output.csv\", table); Files.writeString(Path.of(\"note.txt\"), \"in data\");");
            assertThat(result.success()).as(result.outputs().toString()).isTrue();
            assertThat(Files.readString(data.resolve("note.txt"))).isEqualTo("in data");
            assertThat(Files.readString(data.resolve("output.csv"))).contains("001,42");
        }
    }
}
