package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;

@Service
public class SqlService {
    private final NotebookStore files;
    private final ObjectMapper mapper;
    private final Path credentials;
    private final MavenDependencies dependencies;
    private final Map<String, SavedResult> results = new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public SqlService(NotebookStore files, ObjectMapper mapper) {
        this(files, mapper, Path.of(System.getProperty("user.home"), ".java-lab", "sql-credentials"));
    }
    SqlService(NotebookStore files, ObjectMapper mapper, Path credentials) {
        this(files, mapper, credentials, new MavenDependencies());
    }
    SqlService(NotebookStore files, ObjectMapper mapper, Path credentials, MavenDependencies dependencies) {
        this.files = files;this.mapper = mapper;this.credentials = credentials;this.dependencies = dependencies;
    }
    public record Credentials(String user, String password) {}
    public record CredentialStatus(String user, boolean passwordStored) {}
    public record Table(List<String> columns, List<List<String>> rows, boolean truncated) {}
    public record Result(String id, List<Table> tables, List<Long> updateCounts) {}
    private record SavedResult(String path, Result result) {}
    public record DbTable(String catalog, String schema, String name, String type) {}
    public record Column(String name, String type, int size, boolean nullable) {}
    public record Schema(String product, String version, String quote, List<DbTable> tables, boolean truncated) {}
    @FunctionalInterface private interface ConnectionWork<T> { T run(Connection connection) throws SQLException; }

    private Path credentialFile(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw invalid("Invalid credential ID");
        return credentials.resolve(id + ".json");
    }
    private Credentials readCredentials(String id) throws IOException {
        Path file = credentialFile(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Credentials("", "");
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { return mapper.readValue(stream, Credentials.class); }
    }
    public synchronized CredentialStatus credentialStatus(String id) throws IOException {
        var value = readCredentials(id);return new CredentialStatus(value.user(), !value.password().isEmpty());
    }
    public synchronized CredentialStatus saveCredentials(String id, Credentials value) throws IOException {
        Path file = credentialFile(id);
        if (value.user() == null || value.user().length() > 10000 || value.password() != null && value.password().length() > 10000)
            throw invalid("Invalid database credentials");
        String password = value.password() == null ? readCredentials(id).password() : value.password();
        Files.createDirectories(credentials);
        if (Files.getFileStore(credentials).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rwx------"));
        Path temporary = Files.getFileStore(credentials).supportsFileAttributeView("posix")
                ? Files.createTempFile(credentials, ".credentials-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(credentials, ".credentials-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), new Credentials(value.user(), password));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return new CredentialStatus(value.user(), !password.isEmpty());
    }

    /** Execute the saved UTF-8 file using its portable metadata and local credentials. */
    public Result load(String path) throws IOException {
        if (path == null || !TextFileStore.format(path).equals("sql")) throw invalid("Select a SQL file");
        var document = new TextFileStore(files).read(path);
        return execute(path, document.content(), document.sqlConfig());
    }

    public Result execute(String path, String sql, SqlConfiguration config) throws IOException {
        if (sql == null || sql.isBlank() || sql.length() > TextFileStore.MAX_BYTES) throw invalid("Provide a SQL statement up to 2 MB");
        return withConnection(path, config, connection -> execute(connection, path, sql));
    }

    private <T> T withConnection(String path, SqlConfiguration config, ConnectionWork<T> work) throws IOException {
        files.existingFile(path);
        if (!TextFileStore.format(path).equals("sql")) throw invalid("Select a SQL file");
        if (config == null) throw invalid("Configure the JDBC connection first");
        config.validate();
        var jars = dependencies.resolve(List.of(config.maven()));
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) urls[i] = jars.get(i).toUri().toURL();
        Credentials auth;
        synchronized (this) { auth = readCredentials(config.credentialId()); }
        try (var loader = new URLClassLoader(urls, java.sql.Driver.class.getClassLoader())) {
            Thread thread = Thread.currentThread();ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(loader);
                Driver driver = null;
                for (Driver candidate : ServiceLoader.load(Driver.class, loader)) if (candidate.acceptsURL(config.url())) { driver = candidate;break; }
                if (driver == null) throw invalid("The JDBC package has no driver for this URL");
                Properties properties = new Properties();properties.setProperty("user", auth.user());properties.setProperty("password", auth.password());
                try (Connection connection = driver.connect(config.url(), properties)) {
                    if (connection == null) throw invalid("The JDBC driver does not support this URL");
                    return work.run(connection);
                }
            } finally { thread.setContextClassLoader(previous); }
        } catch (SQLException e) {
            throw invalid("SQL operation failed (SQLState " + Objects.toString(e.getSQLState(), "unknown") + ", code " + e.getErrorCode() + "). Check the statement and connection settings.");
        } catch (ServiceConfigurationError | LinkageError e) { throw invalid("Could not load the JDBC driver. Check the Maven package and Java compatibility"); }
    }

    private Result execute(Connection connection, String path, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(10001);statement.setQueryTimeout(60);
            List<Table> tables = new ArrayList<>();List<Long> updates = new ArrayList<>();
            boolean more = statement.execute(sql);long totalCharacters = 0;
            do {
                if (more) {
                    try (ResultSet rows = statement.getResultSet()) {
                        ResultSetMetaData meta = rows.getMetaData();List<String> columns = new ArrayList<>();List<List<String>> values = new ArrayList<>();
                        for (int col = 1; col <= meta.getColumnCount(); col++) columns.add(meta.getColumnLabel(col));
                        boolean truncated = false;
                        while (rows.next()) {
                            if (values.size() == 10000) { truncated = true;break; }
                            List<String> row = new ArrayList<>();
                            for (int col = 1; col <= columns.size(); col++) {
                                String value = rows.getString(col);row.add(value);totalCharacters += value == null ? 0 : value.length();
                                if (totalCharacters > 4_000_000) throw invalid("SQL result exceeds 4 MB. Restrict the query result");
                            }
                            values.add(row);
                        }
                        tables.add(new Table(columns, values, truncated));
                    }
                } else {
                    long count = statement.getLargeUpdateCount();if (count == -1) break;updates.add(count);
                }
                if (tables.size() + updates.size() > 50) throw invalid("Too many SQL results (maximum 50)");
                more = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            } while (true);
            Result result = new Result(UUID.randomUUID().toString(), tables, updates);
            synchronized (this) {
                while (results.size() >= 10) results.remove(results.keySet().iterator().next());
                results.put(result.id(), new SavedResult(path, result));
            }
            return result;
        }
    }

    public Schema schema(String path, SqlConfiguration config) throws IOException {
        return withConnection(path, config, connection -> {
            var meta = connection.getMetaData();var tables = new ArrayList<DbTable>();boolean truncated = false;
            try (var rows = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE", "VIEW", "BASE TABLE"})) {
                while (rows.next()) {
                    if (tables.size() == 500) { truncated = true;break; }
                    tables.add(new DbTable(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"), rows.getString("TABLE_NAME"), rows.getString("TABLE_TYPE")));
                }
            }
            return new Schema(meta.getDatabaseProductName(), meta.getDatabaseProductVersion(), meta.getIdentifierQuoteString(), tables, truncated);
        });
    }

    private DbTable requireTable(Connection connection, DbTable table) throws SQLException {
        if (table == null || table.name() == null) throw invalid("Select a database table");
        var meta = connection.getMetaData();
        try (var rows = meta.getTables(table.catalog(), pattern(table.schema(), meta), pattern(table.name(), meta), new String[]{"TABLE", "VIEW", "BASE TABLE"})) {
            while (rows.next()) if (Objects.equals(table.catalog(), rows.getString("TABLE_CAT")) && Objects.equals(table.schema(), rows.getString("TABLE_SCHEM")) && table.name().equals(rows.getString("TABLE_NAME"))) return table;
        }
        throw invalid("Database table not found");
    }

    public List<Column> columns(String path, SqlConfiguration config, DbTable table) throws IOException {
        return withConnection(path, config, connection -> {
            requireTable(connection, table);var columns = new ArrayList<Column>();var meta = connection.getMetaData();
            // Read exact identifiers from the result: JDBC patterns can contain wildcards in table names.
            try (var rows = meta.getColumns(table.catalog(), pattern(table.schema(), meta), pattern(table.name(), meta), "%")) {
                while (rows.next()) if (Objects.equals(table.catalog(), rows.getString("TABLE_CAT")) && Objects.equals(table.schema(), rows.getString("TABLE_SCHEM")) && table.name().equals(rows.getString("TABLE_NAME"))) {
                    if (columns.size() == 1000) throw invalid("Table has more than 1000 columns");
                    columns.add(new Column(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
            }
            return columns;
        });
    }

    /** Structured preview only: the model cannot supply executable SQL expressions. */
    public Table preview(String path, SqlConfiguration config, DbTable table) throws IOException {
        return withConnection(path, config, connection -> {
            requireTable(connection, table);
            String quote = connection.getMetaData().getIdentifierQuoteString().trim();
            if (quote.isEmpty()) throw invalid("This driver does not support quoted table previews");
            String name = quoted(table.name(), quote);
            if (table.schema() != null && !table.schema().isEmpty()) name = quoted(table.schema(), quote) + "." + name;
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setMaxRows(21);statement.setQueryTimeout(15);
                try (var rows = statement.executeQuery("SELECT * FROM " + name)) {
                    var labels = new ArrayList<String>();var values = new ArrayList<List<String>>();var meta = rows.getMetaData();
                    if (meta.getColumnCount() > 100) throw invalid("Preview supports at most 100 columns");
                    for (int col = 1; col <= meta.getColumnCount(); col++) labels.add(meta.getColumnLabel(col));
                    int characters = 0;boolean truncated = false;
                    while (rows.next()) {
                        if (values.size() == 20) { truncated = true;break; }
                        var row = new ArrayList<String>();
                        for (int col = 1; col <= labels.size(); col++) {
                            String value = rows.getString(col);
                            if (value != null && value.length() > 200) { value = value.substring(0, 200);truncated = true; }
                            characters += value == null ? 0 : value.length();row.add(value);
                        }
                        if (characters > 16000) { truncated = true;break; }values.add(row);
                    }
                    return new Table(labels, values, truncated);
                }
            } finally { connection.rollback(); }
        });
    }
    private static String quoted(String name, String quote) { return quote + name.replace(quote, quote + quote) + quote; }
    private static String pattern(String name, DatabaseMetaData meta) throws SQLException {
        if (name == null) return null;
        String escape = meta.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) return "%";
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    public synchronized NotebookStore.Upload export(String id, int tableIndex, String path) throws IOException {
        var saved = results.get(id);
        if (saved == null) throw invalid("Result expired. Execute the SQL again before exporting");
        files.existingFile(path);
        if (!TextFileStore.format(path).equals("sql")) throw invalid("Select a SQL file");
        if (tableIndex < 0 || tableIndex >= saved.result().tables().size()) throw invalid("Result table not found");
        var table = saved.result().tables().get(tableIndex);StringWriter output = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            printer.printRecord(table.columns());for (var row : table.rows()) printer.printRecord(row);
        }
        int slash = path.lastIndexOf('/');String folder = slash < 0 ? "" : path.substring(0, slash);
        String name = path.substring(slash + 1).replaceFirst("(?i)\\.sql$", "");
        name = name.substring(0, Math.min(name.length(), 70)) + "-result" + (tableIndex == 0 ? "" : "-" + (tableIndex + 1)) + ".csv";
        return files.upload(folder, name, output.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
