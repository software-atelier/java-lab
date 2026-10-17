package ch.software_atelier.java_lab.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** String-valued CSV tables shared by the notebook kernel and workspace editor. */
public final class Csv {
    public static final int MAX_BYTES = 10000000, MAX_ROWS = 10000, MAX_COLUMNS = 100, MAX_CELLS = 100000;
    private Csv() {}

    public record Table(List<List<String>> rows, char delimiter, String recordSeparator, boolean bom, boolean trailingNewline) {
        public Table {
            checkDelimiter(delimiter);
            if (recordSeparator == null || !List.of("\n", "\r\n", "\r").contains(recordSeparator)) throw new IllegalArgumentException("Invalid CSV record separator");
            if (rows == null || rows.size() > MAX_ROWS) throw new IllegalArgumentException("CSV supports at most 10,000 records");
            int cells = 0; long chars = 0;
            var copy = new ArrayList<List<String>>();
            for (var row : rows) {
                if (row == null || row.isEmpty() || row.size() > MAX_COLUMNS) throw new IllegalArgumentException("CSV records require 1–100 columns");
                cells += row.size();
                if (cells > MAX_CELLS) throw new IllegalArgumentException("CSV supports at most 100,000 cells");
                for (String value : row) {
                    if (value == null || value.length() > 100000) throw new IllegalArgumentException("CSV cells require text of at most 100,000 characters");
                    chars += value.length();
                    if (chars > MAX_BYTES) throw new IllegalArgumentException("CSV table exceeds its size limit");
                }
                copy.add(List.copyOf(row));
            }
            rows = List.copyOf(copy);
        }
        public List<String> headers() { return rows.isEmpty() ? List.of() : rows.get(0); }
        public List<List<String>> data() { return rows.isEmpty() ? List.of() : rows.subList(1, rows.size()); }
        public List<Map<String,String>> records() {
            var headers = headers();
            if (new HashSet<>(headers).size() != headers.size() || headers.stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("Named CSV records require unique, nonblank headers");
            var records = new ArrayList<Map<String,String>>();
            for (var row : data()) {
                if (row.size() != headers.size()) throw new IllegalArgumentException("Named CSV records require the same width as the header");
                var record = new LinkedHashMap<String,String>();
                for (int i = 0; i < headers.size(); i++) record.put(headers.get(i), row.get(i));
                records.add(Collections.unmodifiableMap(record));
            }
            return List.copyOf(records);
        }
        public Table withRows(List<? extends List<String>> replacement) { return new Table(replacement == null ? null : new ArrayList<>(replacement), delimiter, recordSeparator, bom, trailingNewline); }
        @Override public String toString() {
            return "CSV table (" + rows.size() + " records, " + rows.stream().mapToInt(List::size).max().orElse(0) + " columns)";
        }
    }

    public static Table load(String path) throws IOException { return load(Path.of(path)); }
    public static Table load(Path path) throws IOException { return load(path, null); }
    public static Table load(String path, char delimiter) throws IOException { return load(Path.of(path), delimiter); }
    public static Table load(Path path, Character delimiter) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("CSV exceeds 10 MB");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return parse(text, delimiter);
        }
    }

    public static Table parse(String text, Character selectedDelimiter) throws IOException {
        if (text == null || text.length() > MAX_BYTES) throw new IOException("CSV exceeds its size limit");
        boolean bom = text.startsWith("\ufeff");
        if (bom) text = text.substring(1);
        char delimiter = selectedDelimiter == null ? detectDelimiter(text) : selectedDelimiter;
        var rows = new ArrayList<List<String>>();
        try (var parser = CSVParser.parse(text, format(delimiter, "\n"))) {
            int cells = 0;
            for (var record : parser) {
                if (rows.size() >= MAX_ROWS || record.size() > MAX_COLUMNS || (cells += record.size()) > MAX_CELLS)
                    throw new IOException("CSV editor limits: 10,000 records, 100 columns, 100,000 cells");
                rows.add(record.toList());
            }
            return new Table(rows, delimiter, recordSeparator(text), bom, text.endsWith("\n") || text.endsWith("\r"));
        } catch (UncheckedIOException | IllegalArgumentException e) { throw new IOException("Invalid or oversized CSV content", e); }
    }

    public static char detectDelimiter(String text) {
        if (text.startsWith("\ufeff")) text = text.substring(1);
        String sample = text.substring(0, Math.min(text.length(), 65536));
        if (sample.length() < text.length()) {
            int end = lastRecordEnd(sample);
            sample = end == 0 ? "" : sample.substring(0, end);
        }
        int complete = lastRecordEnd(sample);
        if (complete > 0) sample = sample.substring(0, complete);
        char best = ','; int bestScore = -1;
        for (char delimiter : new char[]{',', ';', '\t', '|'}) {
            try (var parser = CSVParser.parse(sample, format(delimiter, "\n"))) {
                var widths = new HashMap<Integer,Integer>(); int count = 0;
                for (var row : parser) {
                    widths.merge(row.size(), 1, Integer::sum);
                    if (++count == 20) break;
                }
                for (var width : widths.entrySet()) if (width.getKey() > 1) {
                    int score = width.getValue() * 1000 / count + Math.min(width.getKey(), 100);
                    if (score > bestScore) { best = delimiter; bestScore = score; }
                }
            } catch (IOException | UncheckedIOException | IllegalArgumentException ignored) { /* Try the next dialect. */ }
        }
        return best;
    }

    private static int lastRecordEnd(String text) {
        boolean quoted = false; int end = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') i++; else quoted = !quoted; }
            else if (!quoted && (c == '\r' || c == '\n')) end = i + 1;
        }
        return end;
    }

    private static String recordSeparator(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') { if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') i++; else quoted = !quoted; }
            else if (!quoted && c == '\n') return "\n";
            else if (!quoted && c == '\r') return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
        }
        return "\r\n";
    }

    public static byte[] encode(Table table) throws IOException {
        var out = new StringWriter();
        try (var printer = new CSVPrinter(out, format(table.delimiter(), table.recordSeparator()))) {
            for (var row : table.rows()) printer.printRecord(row);
        }
        String text = out.toString();
        if (!table.trailingNewline() && !table.rows().isEmpty()) text = text.substring(0, text.length() - table.recordSeparator().length());
        if (table.bom()) text = "\ufeff" + text;
        var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        if (bytes.length > MAX_BYTES) throw new IOException("CSV exceeds 10 MB");
        return bytes;
    }

    public static void save(String path, Table table) throws IOException { save(Path.of(path), table); }
    public static void save(Path path, Table table) throws IOException {
        byte[] bytes = encode(table);
        Path target = path.toAbsolutePath().normalize();
        Path temp = Files.createTempFile(target.getParent(), ".java-lab-csv-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    public static CSVFormat format(char delimiter, String separator) {
        checkDelimiter(delimiter);
        return CSVFormat.RFC4180.builder().setDelimiter(delimiter).setRecordSeparator(separator).get();
    }
    private static void checkDelimiter(char delimiter) {
        if (",;\t|".indexOf(delimiter) < 0) throw new IllegalArgumentException("Use comma, semicolon, tab, or pipe as CSV delimiter");
    }
}
