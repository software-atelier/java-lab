package dev.mars.lab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Local machine overrides, deliberately stored outside shareable notebook documents. */
final class NotebookValues {
    private final ObjectMapper mapper;
    private final Path directory;
    NotebookValues(ObjectMapper mapper, Path notebookRoot) {
        this.mapper = mapper;
        this.directory = notebookRoot.getParent().resolve(".java-lab-values");
    }
    private Path file(Path notebook) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(notebook.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    synchronized Map<String,String> read(Path notebook) throws IOException {
        Path path = file(notebook);
        if (!Files.exists(path)) return Map.of();
        if (Files.isSymbolicLink(path)) throw new IOException("Invalid local values path");
        return mapper.readValue(path.toFile(), new TypeReference<Map<String,String>>(){});
    }
    synchronized void update(Path notebook, Set<String> names, Map<String,String> values, List<String> clear) throws IOException {
        if (values == null) values = Map.of();
        if (clear == null) clear = List.of();
        if (values.size() > 64 || clear.size() > 64) throw NotebookSettings.invalid("Too many variable updates");
        for (var entry : values.entrySet()) if (!names.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().length() > 8192) throw NotebookSettings.invalid("Invalid variable override");
        for (String name : clear) if (!names.contains(name)) throw NotebookSettings.invalid("Unknown variable to clear");
        Map<String,String> merged = new HashMap<>(read(notebook));
        merged.putAll(values);
        clear.forEach(merged::remove);
        Path destination = file(notebook);
        if (merged.isEmpty()) { Files.deleteIfExists(destination); return; }
        Files.createDirectories(directory);
        chmod(directory, true);
        Path temporary = Files.createTempFile(directory, ".values-", ".tmp");
        try {
            chmod(temporary, false);
            mapper.writeValue(temporary.toFile(), merged);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            chmod(destination, false);
        } finally { Files.deleteIfExists(temporary); }
    }
    synchronized void retain(Path notebook, Set<String> names) throws IOException {
        Map<String,String> existing = read(notebook);
        if (!names.containsAll(existing.keySet())) {
            Map<String,String> kept = new HashMap<>(existing);
            kept.keySet().retainAll(names);
            delete(notebook);
            if (!kept.isEmpty()) update(notebook, names, kept, List.of());
        }
    }
    synchronized void rename(Path source, Path target) throws IOException {
        Path from = file(source), to = file(target);
        if (Files.exists(from)) { Files.createDirectories(directory); Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    synchronized void delete(Path notebook) throws IOException { Files.deleteIfExists(file(notebook)); }
    private static void chmod(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path, directory ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE) : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) { path.toFile().setReadable(false, false); path.toFile().setWritable(false, false); path.toFile().setReadable(true, true); path.toFile().setWritable(true, true); }
    }
    record Source(String name, String source) {}
    static List<Source> sources(NotebookSettings.Settings settings, Map<String,String> overrides, Map<String,String> environment) {
        return settings.variables().stream().map(v -> new Source(v.name(), overrides.containsKey(v.name()) ? "override" : v.env() != null && environment.containsKey(v.env()) ? "environment" : v.defaultValue() != null ? "default" : "unset")).toList();
    }
    static Map<String,String> effective(NotebookSettings.Settings settings, Map<String,String> overrides, Map<String,String> environment) {
        Map<String,String> result = new HashMap<>();
        for (var v : settings.variables()) {
            if (overrides.containsKey(v.name())) result.put(v.name(), overrides.get(v.name()));
            else if (v.env() != null && environment.containsKey(v.env())) result.put(v.name(), environment.get(v.env()));
            else if (v.defaultValue() != null) result.put(v.name(), v.defaultValue());
        }
        return result;
    }
}
