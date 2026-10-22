package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlServiceTest {
    @TempDir Path temporary;
    private SqlConfiguration config() { return new SqlConfiguration("com.h2database:h2:2.3.232", "jdbc:h2:mem:" + UUID.randomUUID(), UUID.randomUUID().toString()); }
    private SqlService service(NotebookStore files) throws Exception {
        var resolver = mock(MavenDependencies.class);
        when(resolver.resolve(anyList())).thenReturn(List.of(Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        return new SqlService(files, new ObjectMapper(), temporary.resolve("private/credentials"), resolver);
    }
    @Test void metadataIsHiddenAndRoundTripsWithRevisionAndLineEndings() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));
        Path file = Path.of(files.directory()).resolve("query.sql");
        Files.writeString(file, "\ufeffselect 1;\r\n");var store = new TextFileStore(files);var loaded = store.read("query.sql");
        var config = config();var saved = store.save("query.sql", "select 2;\n", loaded.bom(), loaded.recordSeparator(), loaded.revision(), config);
        assertEquals("select 2;\n", saved.content());assertEquals(config, saved.sqlConfig());
        String raw = Files.readString(file);assertTrue(raw.startsWith("\ufeff-- java-lab-sql: {"));assertTrue(raw.contains("\r\nselect 2;\r\n"));
        assertFalse(raw.contains("password"));
        assertThrows(ResponseStatusException.class, () -> store.save("query.sql", "select 3", false, "\n", loaded.revision(), config));
        var again = store.save("query.sql", saved.content(), saved.bom(), saved.recordSeparator(), saved.revision());
        assertEquals(config, again.sqlConfig());assertEquals(1, Files.readString(file).split("java-lab-sql", -1).length - 1);
    }
    @Test void credentialsAreExternalPrivateAndPasswordsAreNeverReturned() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));var service = service(files);String id = UUID.randomUUID().toString();
        var status = service.saveCredentials(id, new SqlService.Credentials("alice", "secret"));assertEquals("alice", status.user());assertTrue(status.passwordStored());
        assertFalse(new ObjectMapper().writeValueAsString(status).contains("secret"));assertTrue(files.list().isEmpty());
        service.saveCredentials(id, new SqlService.Credentials("bob", null));assertTrue(service.credentialStatus(id).passwordStored());
        Path file = temporary.resolve("private/credentials/" + id + ".json");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        service.saveCredentials(id, new SqlService.Credentials("bob", ""));assertFalse(service.credentialStatus(id).passwordStored());
        assertThrows(ResponseStatusException.class, () -> service.credentialStatus("../escape"));
    }
    @Test void jdbcExecutionAndCsvExportPreserveValuesAndNeverOverwriteFiles() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));files.createFolder("queries");
        Files.writeString(Path.of(files.directory()).resolve("queries/report.sql"), "select 1");var service = service(files);var config = config();
        service.saveCredentials(config.credentialId(), new SqlService.Credentials("sa", "secret"));
        var result = service.execute("queries/report.sql", "SELECT 'a,b' AS \"text\", CAST(NULL AS VARCHAR) AS \"missing\", 9007199254740993 AS \"large\"", config);
        assertEquals(List.of("text", "missing", "large"), result.tables().get(0).columns());
        var row = result.tables().get(0).rows().get(0);assertEquals("a,b", row.get(0));assertNull(row.get(1));assertEquals("9007199254740993", row.get(2));
        var export = service.export(result.id(), 0, "queries/report.sql");assertEquals("queries/report-result.csv", export.path());
        assertEquals("text,missing,large\r\n\"a,b\",,9007199254740993\r\n", Files.readString(files.existingFile(export.path())));
        assertEquals("queries/report-result-1.csv", service.export(result.id(), 0, "queries/report.sql").path());
        assertThrows(ResponseStatusException.class, () -> service.export("expired", 0, "queries/report.sql"));
        var update = service.execute("queries/report.sql", "CREATE TABLE example(id INTEGER)", config);assertEquals(List.of(0L), update.updateCounts());
    }
    @Test void largeResultsAreExplicitlyLimitedAndSqlErrorsDoNotLeakCredentials() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));Files.writeString(Path.of(files.directory()).resolve("query.sql"), "select 1");
        var service = service(files);var config = config();
        var result = service.execute("query.sql", "SELECT X FROM SYSTEM_RANGE(1, 10002)", config);
        assertEquals(10000, result.tables().get(0).rows().size());assertTrue(result.tables().get(0).truncated());
        var error = assertThrows(ResponseStatusException.class, () -> service.execute("query.sql", "SELECT secret_password FROM missing_table", config));
        assertTrue(error.getReason().contains("SQLState"));assertFalse(error.getReason().contains("secret_password"));
        assertThrows(ResponseStatusException.class, () -> new SqlConfiguration(config.maven(), "jdbc:h2:mem:test;PASSWORD=secret", config.credentialId()).validate());
        assertThrows(ResponseStatusException.class, () -> new SqlConfiguration(config.maven(), "jdbc:oracle:thin:alice/secret@localhost:1521:db", config.credentialId()).validate());
        assertThrows(ResponseStatusException.class, () -> service.execute("../query.sql", "select 1", config));
    }
    @Test void schemaColumnsAndReadOnlyPreviewUseExactQuotedIdentifiersAndBoundData() throws Exception {
        var files = new NotebookStore(new ObjectMapper(), temporary.resolve("data"));
        Files.writeString(Path.of(files.directory()).resolve("query.sql"), "");
        var service = service(files);var base = config();
        var config = new SqlConfiguration(base.maven(), "jdbc:h2:file:" + temporary.resolve("schema-db"), base.credentialId());
        service.execute("query.sql", "CREATE TABLE \"odd_%\"(\"a\" INTEGER NOT NULL, \"b\" VARCHAR(500)); INSERT INTO \"odd_%\" SELECT X, REPEAT('x', 300) FROM SYSTEM_RANGE(1, 25)", config);
        var schema = service.schema("query.sql", config);
        assertEquals("H2", schema.product());
        var table = schema.tables().stream().filter(t -> t.name().equals("odd_%")).findFirst().orElseThrow(() -> new AssertionError(schema));
        var columns = service.columns("query.sql", config, table);
        assertEquals(List.of("a", "b"), columns.stream().map(SqlService.Column::name).toList());
        assertFalse(columns.get(0).nullable());assertTrue(columns.get(1).nullable());assertEquals("CHARACTER VARYING", columns.get(1).type());
        var preview = service.preview("query.sql", config, table);
        assertEquals(20, preview.rows().size());assertTrue(preview.truncated());assertEquals(200, preview.rows().get(0).get(1).length());
        assertThrows(ResponseStatusException.class, () -> service.preview("query.sql", config, new SqlService.DbTable(table.catalog(), table.schema(), "odd_%; DROP TABLE x", "TABLE")));
        assertEquals("25", service.execute("query.sql", "SELECT COUNT(*) FROM \"odd_%\"", config).tables().get(0).rows().get(0).get(0));
    }
}
