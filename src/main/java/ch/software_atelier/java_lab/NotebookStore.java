package ch.software_atelier.java_lab;

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
    private final NotebookValues values;

    @org.springframework.beans.factory.annotation.Autowired
    public NotebookStore(ObjectMapper mapper) { this(mapper, Path.of(System.getProperty("user.dir"))); }

    NotebookStore(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
        this.values = new NotebookValues(mapper, this.root);
    }

    public NotebookValues values() { return values; }

    public record Entry(String name, long size, long modified, String type) {}
    public record Folder(String name, String path) {}

    public String directory() { return root.toString(); }

    public List<Entry> list() throws IOException { return list(""); }
    public List<Entry> list(String folder) throws IOException {
        Path directory = resolveFolder(visibleFolderPath(folder), true);
        try (var files = Files.list(directory)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> visibleFilename(p.getFileName().toString()))
                .map(p -> {
                    try {
                        String name = p.getFileName().toString();
                        return new Entry(name, Files.size(p), Files.getLastModifiedTime(p).toMillis(), isNotebookName(name) ? "notebook" : "file");
                    }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }).sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    public List<Folder> folders(String folder) throws IOException {
        Path directory = resolveFolder(visibleFolderPath(folder), true);
        try (var files = Files.list(directory)) {
            return files.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> visibleFilename(p.getFileName().toString()))
                .map(p -> new Folder(p.getFileName().toString(), root.relativize(p).toString().replace('\\', '/')))
                .sorted(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    public synchronized String createFolder(String path) throws IOException {
        Path folder = resolveFolder(path, false);
        if (folder.equals(root)) throw badRequest("Folder name is required");
        if (!Files.isDirectory(folder.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent folder not found");
        try { Files.createDirectory(folder); }
        catch (FileAlreadyExistsException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Folder already exists"); }
        return root.relativize(folder).toString().replace('\\', '/');
    }

    public synchronized void deleteFolder(String path) throws IOException {
        Path folder = resolveFolder(path, true);
        if (folder.equals(root)) throw badRequest("Cannot delete root folder");
        try { Files.delete(folder); }
        catch (DirectoryNotEmptyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Folder is not empty"); }
    }

    public synchronized String renameFolder(String path, String targetName) throws IOException {
        Path source = resolveFolder(path, true);
        if (source.equals(root)) throw badRequest("Cannot rename root folder");
        if (targetName == null || !validSegment(targetName)) throw badRequest("Invalid folder name");
        try (var children = Files.list(source)) {
            if (children.findAny().isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only empty folders can be renamed");
        }
        String parent = root.relativize(source.getParent()).toString().replace('\\', '/');
        Path target = resolveFolder(parent.isEmpty() ? targetName : parent + "/" + targetName, false);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Folder already exists");
        Files.move(source, target);
        return root.relativize(target).toString().replace('\\', '/');
    }

    private Path resolveFolder(String path, boolean mustExist) {
        if (path == null || path.isBlank()) return root;
        String[] segments = path.split("/", -1);
        if (segments.length > 10 || path.length() > 500) throw badRequest("Invalid folder path");
        Path result = root;
        for (String segment : segments) {
            if (!validSegment(segment)) throw badRequest("Invalid folder path");
            result = result.resolve(segment);
            if (Files.isSymbolicLink(result)) throw badRequest("Symbolic links are not allowed in notebook paths");
        }
        if (mustExist && !Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found");
        return result;
    }

    private static boolean validSegment(String segment) {
        return segment != null && !segment.isBlank() && !segment.equals(".") && !segment.equals("..")
            && segment.length() <= 100 && !segment.contains("/") && !segment.contains("\\") && !segment.contains(":") && !segment.contains("\0")
            && segment.chars().noneMatch(c -> c < 32 || c == 127);
    }

    public record Upload(String name, String path, long size, List<List<String>> preview) {}

    public synchronized Upload upload(String folder, String requestedName, byte[] bytes) throws IOException {
        Path directory = resolveFolder(folder, true);
        String lowerName = requestedName == null ? "" : requestedName.toLowerCase(java.util.Locale.ROOT);
        if (!validSegment(requestedName) || requestedName.startsWith(".") || requestedName.length() > 100
            || lowerName.endsWith(".jnb") || lowerName.endsWith(".ipynb")) throw badRequest("Invalid upload filename");
        if (bytes == null || bytes.length == 0 || bytes.length > 10_000_000) throw badRequest("Upload must be 1 byte–10 MB");
        String base = requestedName, suffix = "";
        int dot = requestedName.lastIndexOf('.');
        if (dot > 0) { base = requestedName.substring(0, dot); suffix = requestedName.substring(dot); }
        String name = requestedName;
        for (int counter = 1; Files.exists(directory.resolve(name), LinkOption.NOFOLLOW_LINKS); counter++)
            name = base + "-" + counter + suffix;
        Path target = directory.resolve(name);
        List<List<String>> preview = name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv") ? csvPreview(bytes) : List.of();
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return new Upload(name, root.relativize(target).toString().replace('\\', '/'), bytes.length, preview);
    }

    private List<List<String>> csvPreview(byte[] bytes) {
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<List<String>> rows = new java.util.ArrayList<>();
        try (var parser = org.apache.commons.csv.CSVParser.parse(content, org.apache.commons.csv.CSVFormat.DEFAULT)) {
            for (var record : parser) {
                List<String> columns = new java.util.ArrayList<>();
                for (int col = 0; col < Math.min(record.size(), 30); col++) columns.add(record.get(col));
                rows.add(columns);
                if (rows.size() == 21) break;
            }
        } catch (Exception e) { throw badRequest("Invalid CSV file"); }
        return rows;
    }

    public ObjectNode read(String name) throws IOException {
        Path path = resolve(name);
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
        var document = mapper.readTree(path.toFile());
        if (!(document instanceof ObjectNode notebook)) throw badRequest("Invalid notebook document");
        validate(notebook);
        return notebook;
    }

    public List<NotebookValues.Source> valueSources(String name) throws IOException {
        var notebook = read(name);
        return NotebookValues.sources(NotebookSettings.parse(notebook), values.read(resolve(name)), System.getenv());
    }

    public void updateValues(String name, java.util.Map<String,String> updates, java.util.List<String> clear) throws IOException {
        var notebook = read(name);
        var names = NotebookSettings.parse(notebook).variables().stream().map(NotebookSettings.Variable::name).collect(java.util.stream.Collectors.toSet());
        values.update(resolve(name), names, updates, clear);
    }

    public NotebookSettings.Settings settings(String name) throws IOException { return NotebookSettings.parse(read(name)); }
    public java.util.Map<String,String> effectiveValues(String name) throws IOException {
        return NotebookValues.effective(settings(name), values.read(resolve(name)), System.getenv());
    }

    public synchronized String create(String requested) throws IOException {
        String name = nativeName(requested == null || requested.isBlank() ? "Untitled" : requested);
        Path path = resolve(name);
        if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found");
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
        if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found");
        Path temporary = Files.createTempFile(path.getParent(), ".java-lab-", ".tmp");
        try {
            mapper.writeValue(temporary.toFile(), document);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        var names = NotebookSettings.parse(document).variables().stream().map(NotebookSettings.Variable::name).collect(java.util.stream.Collectors.toSet());
        values.retain(path, names);
    }

    public synchronized String rename(String name, String newName) throws IOException {
        Path source = resolve(name);
        newName = nativeName(newName);
        Path target = resolve(newName);
        if (!Files.exists(source)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
        if (source.equals(target)) return newName;
        if (Files.exists(target)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A notebook with that name already exists");
        Files.move(source, target);
        values.rename(source, target);
        return newName;
    }

    /** Move a listed notebook or data file without changing its filename. */
    public synchronized String move(String sourcePath, String targetFolder) throws IOException {
        Path source = resolveVisibleFile(sourcePath);
        Path destinationDirectory = resolveFolder(visibleFolderPath(targetFolder), true);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        Path target = destinationDirectory.resolve(source.getFileName());
        if (source.equals(target)) return relative(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A file with that name already exists");
        try { Files.move(source, target); }
        catch (FileAlreadyExistsException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "A file with that name already exists"); }
        if (isNotebookName(source.getFileName().toString())) {
            try { values.rename(source, target); }
            catch (IOException | RuntimeException e) {
                try { Files.move(target, source); }
                catch (IOException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
        return relative(target);
    }

    private String relative(Path path) { return root.relativize(path).toString().replace('\\', '/'); }

    private Path resolveVisibleFile(String path) {
        if (path == null || path.isBlank()) throw badRequest("File path is required");
        int slash = path.lastIndexOf('/');
        String filename = path.substring(slash + 1);
        if (!visibleFilename(filename)) throw badRequest("Invalid file path");
        Path result = resolveFolder(visibleFolderPath(slash < 0 ? "" : path.substring(0, slash)), false).resolve(filename);
        if (Files.isSymbolicLink(result)) throw badRequest("Symbolic links are not allowed in file paths");
        return result;
    }

    private static boolean visibleFilename(String name) { return validSegment(name) && !name.startsWith("."); }

    private String visibleFolderPath(String path) {
        if (path == null || path.isBlank()) return "";
        for (String segment : path.split("/", -1))
            if (!visibleFilename(segment)) throw badRequest("Invalid folder path");
        return path;
    }

    public synchronized void delete(String name) throws IOException {
        Path path = resolve(name);
        if (!Files.deleteIfExists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notebook not found");
        values.delete(path);
    }

    private Path resolve(String name) {
        if (name == null || name.isBlank() || name.contains("\\") || !isNotebookName(name))
            throw badRequest("Use a notebook filename ending in .jnb (.ipynb is supported for legacy files)");
        int slash = name.lastIndexOf('/');
        String folder = slash < 0 ? "" : name.substring(0, slash);
        String filename = name.substring(slash + 1);
        if (!validSegment(filename)) throw badRequest("Invalid notebook filename");
        Path path = resolveFolder(folder, false).resolve(filename);
        if (Files.isSymbolicLink(path)) throw badRequest("Invalid notebook path");
        return path;
    }

    private static boolean isNotebookName(String name) { return name.endsWith(".jnb") || name.endsWith(".ipynb"); }

    private static String nativeName(String name) {
        if (name.endsWith(".ipynb")) name = name.substring(0, name.length() - 6);
        return name.endsWith(".jnb") ? name : name + ".jnb";
    }

    private void validate(ObjectNode document) {
        if (document.path("nbformat").asInt() != 4 || !document.path("cells").isArray()) throw badRequest("Expected a version 4 notebook with cells");
        NotebookSettings.parse(document);
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
