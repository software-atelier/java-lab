package ch.software_atelier.java_lab;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.io.IOException;

@RestController
@RequestMapping("/api/sql")
public class SqlController {
    private final SqlService service;
    private final SqlGenerationService generation;
    public SqlController(SqlService service, SqlGenerationService generation) { this.service = service;this.generation = generation; }
    public record SchemaRequest(String path, SqlConfiguration config, SqlService.DbTable table) {}
    @PostMapping("/schema") public ResponseEntity<SqlService.Schema> schema(@RequestBody SchemaRequest request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.schema(request.path(), request.config()));
    }
    @PostMapping("/columns") public ResponseEntity<java.util.List<SqlService.Column>> columns(@RequestBody SchemaRequest request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.columns(request.path(), request.config(), request.table()));
    }
    @PostMapping("/generate") public ResponseEntity<ch.software_atelier.java_lab.ai.CodeGeneration.Result> generate(@RequestBody SqlGenerationService.Request request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(generation.generate(request));
    }
    public record ExecuteRequest(String path, String sql, SqlConfiguration config) {}
    public record ExportRequest(String resultId, int tableIndex, String path) {}
    @GetMapping("/credentials/{id}") public ResponseEntity<SqlService.CredentialStatus> credentials(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.credentialStatus(id));
    }
    @PutMapping("/credentials/{id}") public SqlService.CredentialStatus saveCredentials(@PathVariable String id, @RequestBody SqlService.Credentials value) throws IOException {
        return service.saveCredentials(id, value);
    }
    @PostMapping("/execute") public ResponseEntity<SqlService.Result> execute(@RequestBody ExecuteRequest request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.execute(request.path(), request.sql(), request.config()));
    }
    @PostMapping("/export") public NotebookStore.Upload export(@RequestBody ExportRequest request) throws IOException {
        return service.export(request.resultId(), request.tableIndex(), request.path());
    }
}
