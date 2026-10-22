package ch.software_atelier.java_lab.ai;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class SqlContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("library", "SQL file helper", """
            Sql (ch.software_atelier.java_lab.data.Sql) is pre-imported in Java notebook kernels.
            Use var result = Sql.load("queries/report.sql"); or Sql.load(Path.of("queries/report.sql"));
            This executes the SAVED UTF-8 workspace SQL file anew, using Maven JDBC driver, URL and
            credentialId from its leading -- java-lab-sql: {...} comment and locally stored credentials.
            No manual JDBC connection, driver dependency in notebook settings, or credential literals are needed.
            Paths must be visible workspace-relative .sql paths, relative to LAB_DATA_DIR, not the notebook folder.
            Save connection settings and SQL edits first; unsaved editor drafts are not included.
            Sql.load returns Sql.Result: tables() is List<Sql.Table>; updateCounts() is List<Long>.
            For exactly one result table, result.table(), columns(), rows(), records(), truncated() are conveniences.
            Zero/multiple tables make those conveniences throw; use tables()/updateCounts() instead.
            Each Sql.Table has columns(): List<String>, rows(): List<List<String>>, records(): List<Map<String,String>>,
            and truncated(): boolean. Rows exclude headers; all JDBC values are strings, SQL NULL is Java null.
            records() requires unique nonblank column labels; use SQL aliases or positional rows() for duplicate labels.
            Lists/maps are immutable. Explicitly parse numeric strings for calculations, charts or numeric sorting.
            Example: var result = Sql.load("queries/sales.sql");
            var records = result.records();
            Grid.table().columns(result.columns()).rows(result.rows()).show();
            Loading executes all statements, including updates; multi-result support depends on the JDBC driver.
            A new connection is opened and closed for each call; no ResultSet/Connection resources escape.
            Limits match the SQL editor: 2 MB source, 10,000 rows per table (inspect truncated()),
            4 million value characters per execution, 50 results, requested 60-second statement timeout.
            Notebook execution still has its overall 30-second cell timeout. load may throw IOException.
            A result expression prints only a summary; use Grid explicitly to display data.
            """, Map.of()));
    }
}
