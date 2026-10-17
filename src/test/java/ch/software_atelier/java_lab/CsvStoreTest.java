package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.ai.*;
import ch.software_atelier.java_lab.data.Csv;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CsvStoreTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void editorSavesRoundTripAndRefusesExternalChangesWithoutOverwriting() throws Exception {
        var notebooks = new NotebookStore(mapper, directory);var csv = new CsvStore(notebooks, mapper);
        notebooks.createFolder("Data");
        notebooks.upload("Data", "sales.CSV", "id,amount\r\n001,10\r\n".getBytes());
        var loaded = csv.read("Data/sales.CSV", null);
        var changed = loaded.table().withRows(List.of(List.of("id", "amount"), List.of("001", "20")));
        var saved = csv.save(loaded.path(), changed, loaded.revision());
        assertThat(saved.revision()).isNotEqualTo(loaded.revision());
        assertThat(csv.read(loaded.path(), null).table()).isEqualTo(changed);
        assertThatThrownBy(() -> csv.save(loaded.path(), loaded.table(), loaded.revision()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("changed on disk");
        Files.writeString(directory.resolve(loaded.path()), "external,file\nkeep,me\n");
        assertThatThrownBy(() -> csv.save(loaded.path(), changed, saved.revision())).isInstanceOf(ResponseStatusException.class);
        assertThat(Files.readString(directory.resolve(loaded.path()))).contains("keep,me");
        try(var files = Files.list(directory.resolve("Data"))) { assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp")); }
    }

    @Test void csvApiRejectsTraversalHiddenPathsSymlinksInvalidUtf8AndNonCsvFiles() throws Exception {
        var notebooks = new NotebookStore(mapper, directory);var csv = new CsvStore(notebooks, mapper);
        Files.writeString(directory.resolve("real.csv"), "x\n1\n");
        Files.writeString(directory.resolve(".hidden.csv"), "x\nsecret\n");
        Files.createSymbolicLink(directory.resolve("alias.csv"), directory.resolve("real.csv"));
        Files.createDirectory(directory.resolve("folder"));Files.createSymbolicLink(directory.resolve("link"), directory.resolve("folder"));
        for(String path : List.of("../real.csv", "/real.csv", ".hidden.csv", "alias.csv", "link/data.csv", "notes.txt"))
            assertThatThrownBy(() -> csv.read(path, null)).as(path).isInstanceOf(ResponseStatusException.class);
        Files.write(directory.resolve("invalid.csv"), new byte[]{(byte)0xc3, 0x28});
        assertThatThrownBy(() -> csv.read("invalid.csv", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("UTF-8");
    }

    @Test void aiKnowsSavedCsvPathsAndHeadersButReceivesNoDataRowsAndRefreshesAfterSave() throws Exception {
        var notebooks = new NotebookStore(mapper, directory);var csv = new CsvStore(notebooks, mapper);
        notebooks.createFolder("Data");notebooks.upload("Data", "sales.csv", "name;revenue\nPRIVATE_ROW_DATA;42\n".getBytes());
        Files.createDirectory(directory.resolve(".private"));Files.writeString(directory.resolve(".private/secret.csv"), "private_header\nx\n");
        Files.createSymbolicLink(directory.resolve("linked.csv"), directory.resolve("Data/sales.csv"));
        var context = new WorkspaceCsvContext(csv, mapper);
        var request = new CodeGeneration.Request("Use CSV", new CodeGeneration.Target("", null, null), List.of());
        String before = context.contextFor(request).get(0).content();
        assertThat(before).contains("Data/sales.csv", "name", "revenue").doesNotContain("PRIVATE_ROW_DATA", "private_header", "linked.csv");
        var loaded = csv.read("Data/sales.csv", null);
        csv.save(loaded.path(), loaded.table().withRows(List.of(List.of("product", "sales"), List.of("PRIVATE_NEW_ROW", "99"))), loaded.revision());
        String after = context.contextFor(request).get(0).content();
        assertThat(after).contains("product", "sales").doesNotContain("revenue", "PRIVATE_NEW_ROW");
        assertThat(new DataFormatsContext().contextFor(request).get(0).content()).contains("Csv.load", "Csv.save", "workspace root");
    }

    @Test void csvHelperIsBundledAndPreImportedInRemoteKernel() throws Exception {
        Path input = directory.resolve("kernel.csv");Files.writeString(input, "id,value\n001,42\n");
        Path output = directory.resolve("copied.csv");
        try(var kernels = new KernelService()) {
            String id = kernels.create();
            String load = "var csvData = Csv.load(" + mapper.writeValueAsString(input.toString()) + ");";
            var loaded = kernels.execute(id, load);
            assertThat(loaded.success()).isTrue();
            assertThat(loaded.outputs().toString()).contains("CSV table").doesNotContain("001");
            var save = kernels.execute(id, "Csv.save(" + mapper.writeValueAsString(output.toString()) + ", csvData); csvData.records().get(0).get(\"id\")");
            assertThat(save.success()).as(save.outputs().toString()).isTrue();
            assertThat(save.outputs().toString()).contains("001");
            assertThat(Csv.load(output)).isEqualTo(Csv.load(input));
        }
    }

    @Test void csvCatalogIsBoundedAndMarksOmittedFilesAndColumns() throws Exception {
        var notebooks = new NotebookStore(mapper, directory);var csv = new CsvStore(notebooks, mapper);
        for (int i = 0; i < 51; i++) Files.writeString(directory.resolve("file-" + i + ".csv"), "name,value\nprivate,42\n");
        var catalog = csv.catalog();
        assertThat(catalog.files()).hasSize(50);assertThat(catalog.truncated()).isTrue();
        assertThat(mapper.writeValueAsString(catalog)).doesNotContain("private").hasSizeLessThan(25000);
        for (int i = 0; i < 51; i++) Files.delete(directory.resolve("file-" + i + ".csv"));
        Files.writeString(directory.resolve("wide.csv"), String.join(",", java.util.stream.IntStream.range(0, 25).mapToObj(i -> "column" + i).toList()) + "\n");
        var wide = csv.catalog().files().get(0);
        assertThat((List<?>) wide.get("columns")).hasSize(20);
        assertThat(wide.get("columnsTruncated")).isEqualTo(true);
    }
}
