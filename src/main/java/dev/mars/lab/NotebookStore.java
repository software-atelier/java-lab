package dev.mars.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class NotebookStore {
    private final Path root;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public NotebookStore(ObjectMapper mapper) { this(mapper, Path.of(System.getProperty("user.dir"))); }

    NotebookStore(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
    }

    public record Entry(String name, long size, long modified) {}

    public String directory() { return root.toString(); }

    public List<Entry> list() throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(p -> isNotebookName(p.getFileName().toString()))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .map(p -> {
                    try { return new Entry(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis()); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }).sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    public ObjectNode read(String name) throws IOException {
        Path path = resolve(name);
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
        var document = mapper.readTree(path.toFile());
        if (!(document instanceof ObjectNode notebook)) throw badRequest("Invalid notebook document");
        validate(notebook);
        return notebook;
    }

    public synchronized String create(String requested) throws IOException {
        String name = nativeName(requested == null || requested.isBlank() ? "Untitled" : requested);
        Path path = resolve(name);
        String base = name.substring(0, name.length() - 4);
        int number = 1;
        while (Files.exists(path)) { name = base + "-" + number++ + ".jnb"; path = resolve(name); }
        ObjectNode document = mapper.createObjectNode();
        document.put("nbformat", 4).put("nbformat_minor", 5);
        var metadata = document.putObject("metadata");
        metadata.putObject("java_lab").put("format_version", 1);
        metadata.putObject("kernelspec").put("display_name", "Java (JShell)").put("language", "java").put("name", "java");
        metadata.putObject("language_info").put("name", "java").put("file_extension", ".java");
        var cell = document.putArray("cells").addObject();
        cell.put("id", UUID.randomUUID().toString()).put("cell_type", "code").putNull("execution_count");
        cell.putObject("metadata");
        cell.putArray("source").add("// Welcome to Java Lab\n").add("log(\"Hello, Java!\");");
        cell.putArray("outputs");
        Files.writeString(path, mapper.writeValueAsString(document), StandardOpenOption.CREATE_NEW);
        return name;
    }

    public synchronized void save(String name, ObjectNode document) throws IOException {
        validate(document);
        Path path = resolve(name);
        Path temporary = Files.createTempFile(root, ".java-lab-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), document);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized String rename(String name, String newName) throws IOException {
        Path source = resolve(name);
        newName = nativeName(newName);
        Path target = resolve(newName);
        if (!Files.exists(source)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
        if (source.equals(target)) return newName;
        if (Files.exists(target)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A notebook with that name already exists");
        Files.move(source, target);
        return newName;
    }

    public synchronized void delete(String name) throws IOException {
        if (!Files.deleteIfExists(resolve(name))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
    }

    private Path resolve(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("\0") || !isNotebookName(name))
            throw badRequest("Use a notebook filename ending in .jnb, without directory separators (.ipynb is supported for legacy files)");
        Path path = root.resolve(name).normalize();
        if (!root.equals(path.getParent()) || Files.isSymbolicLink(path)) throw badRequest("Invalid notebook path");
        return path;
    }

    private static boolean isNotebookName(String name) { return name.endsWith(".jnb") || name.endsWith(".ipynb"); }

    private static String nativeName(String name) {
        if (name.endsWith(".ipynb")) name = name.substring(0, name.length() - 6);
        return name.endsWith(".jnb") ? name : name + ".jnb";
    }

    private void validate(ObjectNode document) {
        if (document.path("nbformat").asInt() != 4 || !document.path("cells").isArray()) throw badRequest("Expected a version 4 notebook with cells");
        for (var cell : document.path("cells")) {
            String type = cell.path("cell_type").asText();
            if (!List.of("code", "markdown", "raw").contains(type) || !(cell.path("source").isTextual() || cell.path("source").isArray()))
                throw badRequest("Each cell must have a valid type and source");
            if (cell.path("source").isArray()) {
                for (var line : cell.path("source")) if (!line.isTextual()) throw badRequest("Cell source must contain text");
            }
            if (type.equals("code") && cell.has("outputs") && !cell.path("outputs").isArray())
                throw badRequest("Code cell outputs must be an array");
        }
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
