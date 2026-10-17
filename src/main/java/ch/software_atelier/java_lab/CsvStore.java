package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.data.Csv;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

@Service
public class CsvStore {
    private final NotebookStore notebooks;
    private final ObjectMapper mapper;
    public CsvStore(NotebookStore notebooks, ObjectMapper mapper) { this.notebooks = notebooks; this.mapper = mapper; }
    public record Document(String path, Csv.Table table, String revision, long size) {}
    public record Catalog(List<Map<String,Object>> files, boolean truncated) {}

    public synchronized Document read(String path, Character delimiter) throws IOException {
        byte[] bytes = bytes(notebooks.resolveCsv(path));
        try {
            var table = Csv.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), delimiter);
            return new Document(path, table, hash(bytes), bytes.length);
        } catch (IOException | IllegalArgumentException e) { throw invalid("CSV must be valid UTF-8 and fit the editor limits (10 MB, 10,000 records, 100 columns, 100,000 cells)"); }
    }

    public synchronized Document save(String path, Csv.Table table, String revision) throws IOException {
        Path target = notebooks.resolveCsv(path);
        if (table == null || revision == null || !revision.matches("[a-f0-9]{64}")) throw invalid("CSV table and revision are required");
        byte[] content;
        try { content = Csv.encode(table); }
        catch (IOException | IllegalArgumentException e) { throw invalid("CSV is invalid or exceeds 10 MB"); }
        Path temp = Files.createTempFile(target.getParent(), ".java-lab-csv-", ".tmp");
        try {
            Files.write(temp, content);
            if (!hash(bytes(notebooks.resolveCsv(path))).equals(revision))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "CSV changed on disk. Your edits were kept; reload to read the current file before saving again");
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        return new Document(path, table, hash(content), content.length);
    }

    private byte[] bytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(Csv.MAX_BYTES + 1);
            if (bytes.length > Csv.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV exceeds 10 MB");
            return bytes;
        }
    }

    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Metadata only: no data rows. Work is bounded and hidden directories/symlinks are skipped. */
    public Catalog catalog() throws IOException {
        Path root = Path.of(notebooks.directory());
        var files = new ArrayList<Map<String,Object>>();
        boolean[] truncated = {false}; int[] visited = {0}, dirs = {0}, budget = {24000};
        Files.walkFileTree(root, Set.of(), 11, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                if (++dirs[0] > 500) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (++visited[0] > 5000) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                String name = file.getFileName().toString();
                if (!attrs.isRegularFile() || name.startsWith(".") || !name.toLowerCase(Locale.ROOT).endsWith(".csv")) return FileVisitResult.CONTINUE;
                String path = root.relativize(file).toString().replace('\\', '/');
                Map<String,Object> description;
                try { description = describe(path, attrs.size()); }
                catch (ResponseStatusException e) { return FileVisitResult.CONTINUE; }
                int length = mapper.writeValueAsString(description).length();
                if (length > budget[0] || files.size() >= 50) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                budget[0] -= length; files.add(description);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) { truncated[0] = true; return FileVisitResult.CONTINUE; }
        });
        files.sort(Comparator.comparing(f -> (String) f.get("path"), String.CASE_INSENSITIVE_ORDER));
        return new Catalog(List.copyOf(files), truncated[0]);
    }

    private Map<String,Object> describe(String path, long size) throws IOException {
        Path file = notebooks.resolveCsv(path);
        if (size > Csv.MAX_BYTES) return Map.of("path", path, "size", size, "status", "Exceeds 10 MB; use Commons CSV streaming directly");
        try {
            var sample = headerSample(file);
            char delimiter = Csv.detectDelimiter(sample.text());
            try (var parser = CSVParser.parse(sample.text(), Csv.format(delimiter, "\n"))) {
                var iterator = parser.iterator();
                List<String> first = iterator.hasNext() ? iterator.next().toList() : List.of();
                var headers = first.stream().limit(20).map(s -> s.substring(0, Math.min(80, s.length()))).toList();
                return Map.of("path", path, "size", size, "delimiter", String.valueOf(delimiter), "columns", headers,
                    "columnsTruncated", sample.truncated() || first.size() > 20 || first.stream().anyMatch(s -> s.length() > 80));
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            return Map.of("path", path, "size", size, "status", "CSV metadata unavailable (invalid format or encoding)");
        }
    }

    private record HeaderSample(String text, boolean truncated) {}
    private HeaderSample headerSample(Path file) throws IOException {
        var text = new StringBuilder(); boolean quoted = false;
        try (var reader = new BufferedReader(reader(file))) {
            while (text.length() < 65536) {
                int c = reader.read();
                if (c == -1) return new HeaderSample(text.toString(), false);
                text.append((char)c);
                if (c == '"') quoted = !quoted;
                if (!quoted && (c == '\n' || c == '\r')) return new HeaderSample(text.toString(), false);
            }
        }
        return new HeaderSample(text.toString(), true);
    }

    private Reader reader(Path file) throws IOException {
        var reader = new PushbackReader(new InputStreamReader(Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8.newDecoder()), 1);
        try {
            int first = reader.read(); if (first != -1 && first != '\ufeff') reader.unread(first);
            return reader;
        } catch (IOException | RuntimeException e) { reader.close(); throw e; }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
