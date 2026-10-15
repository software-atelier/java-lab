package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/notebooks")
public class NotebookController {
    private final NotebookStore store;
    public NotebookController(NotebookStore store) { this.store = store; }
    public record NameRequest(String name) {}
    public record FolderRequest(String path) {}
    public record MoveRequest(String path, String folder) {}
    public record ValuesRequest(Map<String,String> values, java.util.List<String> clear) {}

    @GetMapping public Map<String, Object> list(@RequestParam(defaultValue = "") String folder) throws IOException {
        return Map.of("directory", store.directory(), "folder", folder, "files", store.list(folder), "folders", store.folders(folder));
    }
    @PostMapping public Map<String, String> create(@RequestParam(defaultValue = "") String folder, @RequestBody(required = false) NameRequest request) throws IOException {
        String path = join(folder, request == null || request.name() == null || request.name().isBlank() ? "Untitled" : request.name());
        String created = store.create(path);
        return Map.of("name", created.substring(created.lastIndexOf('/') + 1));
    }
    @PostMapping("/folders") public Map<String, String> createFolder(@RequestBody FolderRequest request) throws IOException {
        return Map.of("path", store.createFolder(request.path()));
    }
    @DeleteMapping("/folders") public void deleteFolder(@RequestParam String path) throws IOException { store.deleteFolder(path); }
    @PostMapping("/move") public Map<String, String> move(@RequestBody MoveRequest request) throws IOException {
        String path = store.move(request.path(), request.folder());
        return Map.of("path", path, "folder", path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "",
            "name", path.substring(path.lastIndexOf('/') + 1));
    }
    @PatchMapping("/folders") public Map<String, String> renameFolder(@RequestParam String path, @RequestBody NameRequest request) throws IOException {
        return Map.of("path", store.renameFolder(path, request.name()));
    }
    @PostMapping("/uploads") public NotebookStore.Upload upload(@RequestParam(defaultValue = "") String folder,
        @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > 10_000_000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds 10 MB");
        return store.upload(folder, file.getOriginalFilename(), file.getBytes());
    }
    @GetMapping("/{name}/values") public Map<String, Object> values(@PathVariable String name, @RequestParam(defaultValue = "") String folder) throws IOException { return Map.of("values", store.valueSources(join(folder,name))); }
    @PutMapping("/{name}/values") public void values(@PathVariable String name, @RequestParam(defaultValue = "") String folder, @RequestBody ValuesRequest request) throws IOException {
        store.updateValues(join(folder,name), request.values(), request.clear());
    }
    @GetMapping("/{name}") public ObjectNode read(@PathVariable String name, @RequestParam(defaultValue = "") String folder) throws IOException { return store.read(join(folder,name)); }
    @PutMapping("/{name}") public void save(@PathVariable String name, @RequestParam(defaultValue = "") String folder, @RequestBody ObjectNode document) throws IOException { store.save(join(folder,name), document); }
    @PatchMapping("/{name}") public Map<String, String> rename(@PathVariable String name, @RequestParam(defaultValue = "") String folder, @RequestBody NameRequest request) throws IOException {
        if (request.name() == null || request.name().isBlank()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Name is required");
        String renamed = store.rename(join(folder,name), join(folder,request.name()));
        return Map.of("name", renamed.substring(renamed.lastIndexOf('/') + 1));
    }
    @DeleteMapping("/{name}") public void delete(@PathVariable String name, @RequestParam(defaultValue = "") String folder) throws IOException { store.delete(join(folder,name)); }
    private static String join(String folder, String name) {
        if (folder == null || folder.isBlank()) return name;
        return folder + "/" + name;
    }
}
