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
    public NotebookStore(ObjectMapper mapper, @org.springframework.beans.factory.annotation.Value("${lab.data-dir:data}") String configuredDirectory) {
        this(mapper, dataDirectory(Path.of(System.getProperty("user.dir")), configuredDirectory));
    }

    public NotebookStore(ObjectMapper mapper) { this(mapper, "data"); }

    static Path dataDirectory(Path workingDirectory, String configuredDirectory) {
        Path path = Path.of(configuredDirectory == null || configuredDirectory.isBlank() ? "data" : configuredDirectory.strip());
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).toAbsolutePath().normalize();
    }

    NotebookStore(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException e) { throw new IllegalStateException("Cannot create or access the configured data directory", e); }
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
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .map(p -> {
                    try {
                        String name = p.getFileName().toString();
                        return new Entry(name, Files.size(p), Files.getLastModifiedTime(p).toMillis(), fileType(name));
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
        return validName(segment, 100);
    }

    private static boolean validName(String segment, int maximumLength) {
        return segment != null && !segment.isBlank() && !segment.equals(".") && !segment.equals("..")
            && segment.length() <= maximumLength && !segment.contains("/") && !segment.contains("\\") && !segment.contains(":") && !segment.contains("\0")
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
        List<List<String>> rows = new java.util.ArrayList<>();
        try {
            String content = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (content.startsWith("\ufeff")) content = content.substring(1);
            try (var parser = org.apache.commons.csv.CSVParser.parse(content, ch.software_atelier.java_lab.data.Csv.format(ch.software_atelier.java_lab.data.Csv.detectDelimiter(content), "\n"))) {
                for (var record : parser) {
                    List<String> columns = new java.util.ArrayList<>();
                    for (int col = 0; col < Math.min(record.size(), 30); col++) columns.add(record.get(col));
                    rows.add(columns);
                    if (rows.size() == 21) break;
                }
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
        moveRenamedFile(source,target);
        try {values.rename(source, target);}
        catch(IOException|RuntimeException e){try{moveRenamedFile(target,source);}catch(IOException|RuntimeException rollback){e.addSuppressed(rollback);}throw e;}
        return newName;
    }

    public record FileEntry(String name, String path, String type, String format) {}
    private FileEntry entry(Path path) {
        String name=path.getFileName().toString(),type=fileType(name);
        return new FileEntry(name,relative(path),type,type.equals("text")?TextFileStore.format(name):null);
    }
    public FileEntry fileInfo(String path){return entry(existingFile(path));}
    public synchronized FileEntry createFile(String folder,String name,String extension) throws IOException {
        var supported=java.util.Set.of("csv","json","xml","txt","md","yaml","yml","java","sql","properties","log","css","js","html","htm");
        if(extension==null||!supported.contains(extension))throw badRequest("Choose a supported new file type");
        if(name==null||name.isBlank())name="Untitled."+extension;
        if(extension.equals("yaml")&&name.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"))extension="yml";
        if(extension.equals("html")&&name.toLowerCase(java.util.Locale.ROOT).endsWith(".htm"))extension="htm";
        if(!name.toLowerCase(java.util.Locale.ROOT).endsWith("."+extension)){
            if(name.contains("."))throw badRequest("Use a filename ending in ."+extension);
            name+="."+extension;
        }
        if(!validName(name,255)||name.startsWith("."))throw badRequest("Invalid file name");
        Path directory=resolveFolder(visibleFolderPath(folder),true);
        String base=name.substring(0,name.lastIndexOf('.')),suffix=name.substring(name.lastIndexOf('.'));
        Path target=directory.resolve(name);int counter=1;
        while(Files.exists(target,LinkOption.NOFOLLOW_LINKS))target=directory.resolve(base+"-"+counter++ +suffix);
        String initial=switch(extension){case "csv"->"column1,column2\n";case "json"->"{\n}\n";case "xml"->"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>\n";default->"";};
        Files.writeString(target,initial,StandardOpenOption.CREATE_NEW);return entry(target);
    }
    public synchronized FileEntry renameFile(String path,String newName) throws IOException {
        Path source=existingFile(path);
        if(!validName(newName,255)||newName.startsWith("."))throw badRequest("Use a visible filename without path separators");
        if(isNotebookName(source.getFileName().toString())){
            String parent=relative(source.getParent());String renamed=rename(path,parent.isEmpty()?newName:parent+"/"+newName);return entry(resolve(renamed));
        }
        Path target=resolveVisibleFile(relative(source.getParent()).isEmpty()?newName:relative(source.getParent())+"/"+newName);
        if(source.equals(target))return entry(target);
        moveRenamedFile(source,target);return entry(target);
    }

    private void moveRenamedFile(Path source,Path target) throws IOException {
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)){Files.move(source,target);return;}
        boolean caseAlias=source.getFileName().toString().equalsIgnoreCase(target.getFileName().toString())&&Files.isSameFile(source,target);
        if(caseAlias)try(var entries=Files.list(source.getParent())){caseAlias=entries.noneMatch(file->file.getFileName().toString().equals(target.getFileName().toString()));}
        if(!caseAlias)throw new ResponseStatusException(HttpStatus.CONFLICT,"A file with that name already exists");
        // Force a case-only rename on case-insensitive filesystems without replacing another entry.
        Path temporary=Files.createTempFile(source.getParent(),".java-lab-rename-",".tmp");
        boolean originalInTemporary=false;
        try{
            Files.move(source,temporary,StandardCopyOption.REPLACE_EXISTING);originalInTemporary=true;
            try{Files.move(temporary,target);originalInTemporary=false;}
            catch(IOException e){try{Files.move(temporary,source);originalInTemporary=false;}catch(IOException rollback){e.addSuppressed(rollback);}throw e;}
        }finally{if(!originalInTemporary)Files.deleteIfExists(temporary);}
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

    Path resolveVisibleFile(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")) throw badRequest("A relative file path is required");
        int slash = path.lastIndexOf('/');
        String filename = path.substring(slash + 1);
        if (!validName(filename, 255) || filename.startsWith(".")) throw badRequest("Invalid file path");
        Path result = resolveFolder(visibleFolderPath(slash < 0 ? "" : path.substring(0, slash)), false).resolve(filename);
        if (Files.isSymbolicLink(result)) throw badRequest("Symbolic links are not allowed in file paths");
        return result;
    }

    Path existingFile(String path) {
        Path file = resolveVisibleFile(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        return file;
    }

    static String fileType(String name) {
        if (isNotebookName(name)) return "notebook";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv")) return "csv";
        if (lower.matches(".*\\.(png|jpe?g|gif|bmp|webp|svg)$")) return "image";
        String extension = lower.substring(lower.lastIndexOf('.') + 1);
        return java.util.Set.of("json","xml","txt","md","yaml","yml","java","sql","properties","log","css","js","html","htm").contains(extension) ? "text" : "file";
    }

    Path resolveCsv(String path) {
        if (path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) throw badRequest("Use a CSV filename ending in .csv");
        Path resolved = resolveVisibleFile(path);
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CSV file not found");
        return resolved;
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
        if (!validName(filename, 255)) throw badRequest("Invalid notebook filename");
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
