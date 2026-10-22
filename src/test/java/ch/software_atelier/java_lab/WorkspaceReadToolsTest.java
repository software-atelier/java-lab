package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceReadToolsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void readsAndPaginatesSavedDataWithoutChangingFiles() throws Exception {
        Files.createDirectory(directory.resolve("sources"));
        String source = "name,value\nAda,42\n" + "x".repeat(5000);
        Files.writeString(directory.resolve("sources/data.csv"), source);
        var tools = new WorkspaceReadTools(new NotebookStore(mapper, directory));
        var listing = mapper.valueToTree(tools.execute("list_workspace_files", mapper.readTree("{\"folder\":\"sources\"}")));
        assertThat(listing.path("entries").get(0).path("name").asText()).isEqualTo("data.csv");
        var first = mapper.valueToTree(tools.execute("read_workspace_file", mapper.readTree("{\"path\":\"sources/data.csv\"}")));
        var rest = mapper.valueToTree(tools.execute("read_workspace_file", mapper.readTree("{\"path\":\"sources/data.csv\",\"offset\":3000}")));
        assertThat(first.path("content").asText() + rest.path("content").asText()).isEqualTo(source);
        assertThat(first.path("nextOffset").asInt()).isEqualTo(3000);
        assertThat(Files.readString(directory.resolve("sources/data.csv"))).isEqualTo(source);
    }

    @Test void rejectsTraversalSymlinksBinaryAndOversizedFiles() throws Exception {
        Files.writeString(directory.resolve("valid.txt"), "data");
        Files.createSymbolicLink(directory.resolve("link.txt"), directory.resolve("valid.txt"));
        Files.write(directory.resolve("binary.bin"), new byte[]{0, 1});
        Files.writeString(directory.resolve("large.txt"), "x".repeat(TextFileStore.MAX_BYTES + 1));
        var tools = new WorkspaceReadTools(new NotebookStore(mapper, directory));
        for (String path : new String[]{"../outside.txt", "/etc/passwd", "link.txt", "binary.bin", "large.txt"}) {
            var result = mapper.valueToTree(tools.execute("read_workspace_file", mapper.createObjectNode().put("path", path)));
            assertThat(result.has("error")).as(path).isTrue();
        }
        assertThat(mapper.valueToTree(tools.execute("read_workspace_file", mapper.readTree("{\"path\":\"valid.txt\",\"offset\":-1}"))).has("error")).isTrue();
    }
}
