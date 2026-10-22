package ch.software_atelier.java_lab.data;

import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Loads saved workspace SQL through Java Lab's JDBC service, including comment settings. */
public final class Sql {
    private Sql() {}

    /** Immutable, string-valued JDBC table. SQL NULL remains Java null. */
    public record Table(List<String> columns, List<List<String>> rows, boolean truncated) {
        public Table {
            columns = List.copyOf(columns);
            rows = rows.stream().map(row -> Collections.unmodifiableList(new ArrayList<>(row))).toList();
        }
        /** Named rows require unique, nonblank column labels; duplicate labels remain usable via rows(). */
        public List<Map<String,String>> records() {
            if (columns.stream().anyMatch(String::isBlank) || new HashSet<>(columns).size() != columns.size())
                throw new IllegalArgumentException("Named SQL records require unique, nonblank column labels; use SQL aliases or rows()");
            var records = new ArrayList<Map<String,String>>();
            for (var row : rows) {
                var record = new LinkedHashMap<String,String>();
                for (int i = 0; i < columns.size(); i++) record.put(columns.get(i), row.get(i));
                records.add(Collections.unmodifiableMap(record));
            }
            return List.copyOf(records);
        }
        @Override public String toString() { return "SQL table (" + rows.size() + " rows, " + columns.size() + " columns" + (truncated ? ", truncated" : "") + ")"; }
    }

    public record Result(List<Table> tables, List<Long> updateCounts) {
        public Result { tables = List.copyOf(tables);updateCounts = List.copyOf(updateCounts); }
        /** Convenience access for a query returning exactly one table, including an empty table. */
        public Table table() {
            if (tables.size() != 1) throw new IllegalStateException("Expected one SQL result table, got " + tables.size() + "; use tables() or updateCounts()");
            return tables.get(0);
        }
        public List<String> columns() { return table().columns(); }
        public List<List<String>> rows() { return table().rows(); }
        public List<Map<String,String>> records() { return table().records(); }
        public boolean truncated() { return table().truncated(); }
        @Override public String toString() { return "SQL result (" + tables.size() + " tables, update counts " + updateCounts + ")"; }
    }

    public static Result load(Path path) throws IOException { return load(path.toString()); }

    /** Executes the saved file anew. Paths are relative to the data root; editor drafts are excluded. */
    public static Result load(String path) throws IOException {
        String endpoint = System.getProperty("java.lab.sql.endpoint");
        if (endpoint == null || endpoint.isBlank()) throw new IOException("SQL helper requires a Java Lab notebook kernel");
        var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(65))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("path", path).toString())).build();
        try {
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            var json = new JSONObject(response.body());
            if (response.statusCode() != 200) throw new IOException(json.optString("error", "SQL load failed"));
            var tables = new ArrayList<Table>();
            for (Object value : json.getJSONArray("tables")) {
                var table = (JSONObject)value;
                var columns = table.getJSONArray("columns").toList().stream().map(String.class::cast).toList();
                var rows = new ArrayList<List<String>>();
                for (Object row : table.getJSONArray("rows")) {
                    var values = new ArrayList<String>();
                    for (Object cell : (org.json.JSONArray)row) values.add(cell == JSONObject.NULL ? null : (String)cell);
                    rows.add(values);
                }
                tables.add(new Table(columns, rows, table.getBoolean("truncated")));
            }
            var counts = json.getJSONArray("updateCounts").toList().stream().map(value -> ((Number)value).longValue()).toList();
            return new Result(tables, counts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();throw new IOException("SQL load interrupted", e);
        }
    }
}
