package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlGenerationServiceTest {
    @Test void suppliesSchemaAndOnlyStructuredReadToolsWithoutCredentialsOrExecutingGeneratedSql() throws Exception {
        var sql = mock(SqlService.class);var provider = mock(OpenAiCompatibleProvider.class);var mapper = new ObjectMapper();
        var config = new SqlConfiguration("org.example:jdbc:1", "jdbc:example:db", "11111111-1111-1111-1111-111111111111");
        var table = new SqlService.DbTable(null, "public", "people", "TABLE");
        when(sql.schema("query.sql", config)).thenReturn(new SqlService.Schema("ExampleDB", "1", "\"", List.of(table), false));
        when(sql.columns("query.sql", config, table)).thenReturn(List.of(new SqlService.Column("name", "VARCHAR", 100, true)));
        when(sql.preview("query.sql", config, table)).thenReturn(new SqlService.Table(List.of("name"), List.of(List.of("Ada")), false));
        when(provider.generateSql(anyList(), any(), any())).thenAnswer(call -> {
            CodeGeneration.Observer observer = call.getArgument(2);
            observer.accept("Reading bounded database sample rows…");
            observer.response(new CodeGeneration.ResponseDiagnostics(1, "model", "model", 1000, "stop", 100L, 20L, null));
            List<CodeGeneration.Message> messages = call.getArgument(0);String context = messages.toString();
            assertTrue(context.contains("people"));assertTrue(context.contains("VARCHAR"));assertFalse(context.contains(config.url()));assertFalse(context.contains(config.credentialId()));
            CodeGeneration.Tools tools = call.getArgument(1);assertEquals(2, tools.definitions().size());
            assertInstanceOf(SqlService.Table.class, tools.execute("sql_preview", mapper.readTree("{\"tableIndex\":0}")));
            assertTrue(tools.execute("execute_sql", mapper.readTree("{\"tableIndex\":0,\"sql\":\"DELETE FROM people\"}")).toString().contains("error"));
            assertTrue(tools.execute("sql_preview", mapper.readTree("{\"tableIndex\":99}")).toString().contains("error"));
            return new CodeGeneration.Result("SELECT name FROM people", "model");
        });
        var progress = new GenerationProgress();String id = java.util.UUID.randomUUID().toString();
        var result = new SqlGenerationService(sql, provider, mapper, progress).generate(new SqlGenerationService.Request("query.sql", config, "List names", "", null, null, "Active people", id));
        assertEquals("SELECT name FROM people", result.code());verify(sql, never()).execute(anyString(), anyString(), any());
        assertTrue(progress.snapshot(id).done());assertEquals(1, progress.snapshot(id).diagnostics().size());
        assertTrue(progress.snapshot(id).events().stream().anyMatch(event -> event.activity().contains("sample rows")));
    }
}
