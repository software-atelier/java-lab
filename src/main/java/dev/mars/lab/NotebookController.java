package dev.mars.lab;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/notebooks")
public class NotebookController {
    private final NotebookStore store;
    public NotebookController(NotebookStore store) { this.store = store; }
    public record NameRequest(String name) {}

    @GetMapping public Map<String, Object> list() throws IOException { return Map.of("directory", store.directory(), "files", store.list()); }
    @PostMapping public Map<String, String> create(@RequestBody(required = false) NameRequest request) throws IOException {
        return Map.of("name", store.create(request == null ? null : request.name()));
    }
    @GetMapping("/{name}") public ObjectNode read(@PathVariable String name) throws IOException { return store.read(name); }
    @PutMapping("/{name}") public void save(@PathVariable String name, @RequestBody ObjectNode document) throws IOException { store.save(name, document); }
    @PatchMapping("/{name}") public Map<String, String> rename(@PathVariable String name, @RequestBody NameRequest request) throws IOException {
        if (request.name() == null || request.name().isBlank()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Name is required");
        return Map.of("name", store.rename(name, request.name()));
    }
    @DeleteMapping("/{name}") public void delete(@PathVariable String name) throws IOException { store.delete(name); }
}
