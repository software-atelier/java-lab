package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.data.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlHelperTest {
    @TempDir Path temporary;

    private MavenDependencies resolver() throws Exception {
        var resolver = mock(MavenDependencies.class);
        when(resolver.resolve(anyList())).thenReturn(List.of(Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        return resolver;
    }

    @Test void savedMetadataAndLocalCredentialsWorkInsideRemoteNotebookKernel() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));
        files.createFolder("queries");
        Path query = Path.of(files.directory()).resolve("queries/report.sql");
        Files.writeString(query, "");
        var resolver = resolver();
        var sql = new SqlService(files, new ObjectMapper(), temporary.resolve("credentials"), resolver);
        var config = new SqlConfiguration("com.h2database:h2:2.3.232", "jdbc:h2:file:" + temporary.resolve("database"), UUID.randomUUID().toString());
        sql.saveCredentials(config.credentialId(), new SqlService.Credentials("alice", "local-secret"));
        sql.execute("queries/report.sql", "CREATE TABLE report(name VARCHAR, amount INTEGER); INSERT INTO report VALUES ('Ada', 42), (NULL, 7)", config);
        Files.writeString(query, "\ufeff" + config.header().replace("\n", "\r\n") + "SELECT name AS \"name\", amount AS \"amount\" FROM report ORDER BY amount DESC;\r\n");

        try (var kernels = new KernelService(files, resolver, sql)) {
            String id = kernels.create();
            var loaded = kernels.execute(id, "var report = Sql.load(Path.of(\"queries/report.sql\"));");
            assertTrue(loaded.success(), loaded.outputs().toString());
            var inspected = kernels.execute(id, "log(\"%/%/%/%\", report.records().get(0).get(\"amount\"), report.rows().get(1).get(0) == null, report.truncated(), report.columns());");
            assertTrue(inspected.success(), inspected.outputs().toString());
            assertTrue(inspected.outputs().toString().contains("42/true/false/[name, amount]"), inspected.outputs().toString());
            assertFalse(inspected.outputs().toString().contains("local-secret"));
            var displayed = kernels.execute(id, "Grid.table().columns(report.columns()).rows(report.rows()).show();");
            assertTrue(displayed.success(), displayed.outputs().toString());
            assertTrue(displayed.outputs().toString().contains("application/vnd.gridjs+json"));

            // A second call reads changed disk content, rather than reusing an editor/result snapshot.
            Files.writeString(query, config.header() + "UPDATE report SET amount = amount + 1");
            var updated = kernels.execute(id, "var update = Sql.load(\"queries/report.sql\"); log(\"Counts: %\", update.updateCounts());");
            assertTrue(updated.success(), updated.outputs().toString());
            assertTrue(updated.outputs().toString().contains("Counts: [2]"));
            var noTable = kernels.execute(id, "update.records();");
            assertFalse(noTable.success());
            assertTrue(noTable.outputs().toString().contains("Expected one SQL result table"));
        }
        verify(resolver, atLeast(3)).resolve(List.of(config.maven()));
    }

    @Test void savedLoadRejectsMissingOrMalformedSettingsAndInvalidPaths() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));
        Path query = Path.of(files.directory()).resolve("query.sql");
        var sql = new SqlService(files, new ObjectMapper(), temporary.resolve("credentials"), resolver());
        Files.writeString(query, "SELECT 1");
        assertTrue(assertThrows(ResponseStatusException.class, () -> sql.load("query.sql")).getReason().contains("Configure"));
        Files.writeString(query, "-- java-lab-sql: {broken}\nSELECT 1");
        assertTrue(assertThrows(ResponseStatusException.class, () -> sql.load("query.sql")).getReason().contains("metadata"));
        assertThrows(ResponseStatusException.class, () -> sql.load("../query.sql"));
        assertThrows(ResponseStatusException.class, () -> sql.load("query.txt"));
    }

    @Test void tablesPreserveNullsAndDuplicateLabelsWithoutLeakingMutability() {
        var row = new ArrayList<>(Arrays.asList("Ada", null));
        var table = new Sql.Table(List.of("name", "missing"), List.of(row), true);
        row.set(0, "Changed");
        assertEquals("Ada", table.rows().get(0).get(0));
        assertNull(table.records().get(0).get("missing"));
        assertThrows(UnsupportedOperationException.class, () -> table.rows().get(0).set(0, "x"));
        assertThrows(UnsupportedOperationException.class, () -> table.records().get(0).put("name", "x"));
        assertTrue(table.truncated());
        var duplicate = new Sql.Table(List.of("x", "x"), List.of(List.of("1", "2")), false);
        assertEquals("2", duplicate.rows().get(0).get(1));
        assertThrows(IllegalArgumentException.class, duplicate::records);
        assertThrows(IllegalStateException.class, () -> new Sql.Result(List.of(table, duplicate), List.of()).records());
    }
}
