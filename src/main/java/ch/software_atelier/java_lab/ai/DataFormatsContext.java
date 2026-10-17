package ch.software_atelier.java_lab.ai;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class DataFormatsContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("library","Bundled JSON and CSV libraries","""
            org.json:json 20260814 (latest Maven Central release checked 2026-10-12) is bundled.
            org.json.* is pre-imported: JSONObject, JSONArray, JSONTokener, JSONPointer, etc.
            Parse with new JSONObject(String) or new JSONArray(String), build with put(), access with get/opt methods,
            pretty-print with toString(2), and bridge to Java/ Grid data with JSONObject.toMap() or JSONArray.toList().
            JSONObject.NULL represents JSON null; put(key, (Object)null) removes a key.
            Example: var data = new JSONObject().put("name", "Ada").put("sales", 42);
            log("Sales for %: %", data.getString("name"), data.getInt("sales"));

            Apache Commons CSV 1.14.1 is bundled, including Commons IO 2.20.0 and Commons Codec 1.19.0.
            org.apache.commons.csv.* and org.apache.commons.io.input.BOMInputStream are pre-imported.
            Use CSVFormat.RFC4180 or DEFAULT and the builder; never parse CSV with String.split.
            It handles quoted delimiters, doubled quotes, embedded newlines, CRLF, empty fields, headers,
            and configurable delimiters/escaping/record separators. CSVRecord values are strings; parse numbers
            explicitly for numeric charts/table sorting. Close CSVParser/Readers using try-with-resources.
            Header format: CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().
            Parse a string with CSVParser.parse(csvText, format) or a Reader with format.parse(reader).
            For TSV use CSVFormat.TDF; for semicolon files use builder().setDelimiter(';').get().
            Use CSVPrinter for correctly quoted CSV output. Malformed input is reported via IOException/CSVException.
            For UTF-8 BOM files, wrap BOMInputStream.builder().setPath(Path.of("data.csv")).get()
            in new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8).

            Csv (ch.software_atelier.java_lab.data.Csv) is also pre-imported for workspace CSV files.
            Prefer it for loading/saving moderate tables: var salesCsv = Csv.load("Data/sales.csv");
            var salesRows = salesCsv.records(); // immutable List<Map<String,String>>, first row is the header
            salesCsv.rows() includes every record, including the first; salesCsv.headers() returns the first
            record, and salesCsv.data() returns the remaining rows. Values remain strings, including leading
            zeros and empty cells. records() requires unique nonblank headers and equal-width rows.
            Csv.load(String) / load(Path) auto-detect comma, semicolon, tab or pipe and preserve UTF-8 BOM,
            line endings and whether the file ended with a newline. load(String, char) selects a delimiter.
            To change data, copy the immutable rows into mutable lists and use table.withRows(newRows).
            Csv.save("Data/copy.csv", salesCsv) / save(Path, table) writes atomically where supported.
            Paths are relative to the workspace root (configured data directory, LAB_DATA_DIR; default workingDir/data), NOT to the notebook's subfolder. Use exact paths from
            the workspace-csv catalog. Its columns are bounded first-record metadata, not verified data rows;
            catalogTruncated means more files/columns may exist. Unsaved table-editor changes are not included.
            Helper/editor limits: 10 MB, 10,000 records including header, 100 columns, 100,000 cells,
            100,000 characters per cell. Use streaming Commons CSV directly for larger files.
            Loading/saving useful data must remain in the generated cell, not be replaced by a printed preview.

            Runnable header CSV example:
            var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();
            try (var parser = CSVParser.parse("name,sales\\nAda,42\\nGrace,19\\n", format)) {
                var table = Grid.table().title("CSV data").columns("Name", "Sales");
                for (CSVRecord row : parser) table.row(row.get("name"), Integer.parseInt(row.get("sales")));
                table.show();
            }
            """,Map.of("jsonVersion","20260814","csvVersion","1.14.1")));
    }
}
