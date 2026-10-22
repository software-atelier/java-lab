package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DatabaseReadToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DatabaseReadTools tools = new DatabaseReadTools();

    private ObjectNode args(String url, String query) {
        return mapper.createObjectNode().put("jdbcUrl", url).put("user", "sa").put("password", "secret").put("query", query);
    }

    @Test void readsColumnLabelsNullsAndCtesWithProvidedCredentials() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "sa", "secret")) {
            connection.createStatement().execute("create table items (id int, name varchar); insert into items values (1, 'Widget'), (2, null)");
            var result = mapper.valueToTree(tools.execute("read_database", args(url,
                "with selected as (select * from items) select id as item_id, name from selected order by id;")));
            assertThat(result.path("columns").get(0).asText()).isEqualTo("ITEM_ID");
            assertThat(result.path("rows").size()).isEqualTo(2);
            assertThat(result.path("rows").get(0).get(1).asText()).isEqualTo("Widget");
            assertThat(result.path("rows").get(1).get(1).isNull()).isTrue();
            assertThat(result.path("truncated").asBoolean()).isFalse();
        }
    }

    @Test void boundsRowsAndCellText() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, "sa", "secret")) {
            var rows = mapper.valueToTree(tools.execute("read_database", args(url, "select x from system_range(1, 150)")));
            assertThat(rows.path("rows").size()).isEqualTo(100);
            assertThat(rows.path("truncated").asBoolean()).isTrue();
            var text = mapper.valueToTree(tools.execute("read_database", args(url, "select repeat('x', 1000)")));
            assertThat(text.path("rows").get(0).get(0).asText()).hasSize(500);
            assertThat(text.path("truncated").asBoolean()).isTrue();
        }
    }

    @Test void rejectsWritesAndMultipleStatementsBeforeConnecting() {
        for (String query : new String[]{"delete from items", "select 1; drop table items", "with deleted as (delete from items returning *) select * from deleted", "select * into copy from items", "select 1 /* comment */"}) {
            var result = mapper.valueToTree(tools.execute("read_database", args("jdbc:h2:mem:unused", query)));
            assertThat(result.path("error").asText()).contains("read-only");
        }
    }

    @Test void validatesArgumentsAndDoesNotEchoConnectionSecretsInFailures() {
        assertThat(mapper.valueToTree(tools.execute("read_database", args("jdbc:unknown:private-host", "select 1"))).toString())
            .contains("error").doesNotContain("secret", "private-host");
        var missingPassword = args("jdbc:h2:mem:test", "select 1");
        missingPassword.remove("password");
        assertThat(mapper.valueToTree(tools.execute("read_database", missingPassword)).has("error")).isTrue();
        var failure = mapper.valueToTree(tools.execute("read_database", args("jdbc:h2:mem:test", "select * from private_table")));
        assertThat(failure.path("error").asText()).isEqualTo("Database read failed");
        assertThat(failure.has("sqlState")).isTrue();
        assertThat(failure.toString()).doesNotContain("secret", "private_table");
    }
}
