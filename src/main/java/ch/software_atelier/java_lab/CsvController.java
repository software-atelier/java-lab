package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.data.Csv;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/csv")
public class CsvController {
    private final CsvStore store;
    public CsvController(CsvStore store) { this.store = store; }
    public record SaveRequest(Csv.Table table, String revision) {}
    @GetMapping public org.springframework.http.ResponseEntity<CsvStore.Document> read(@RequestParam String path, @RequestParam(required = false) Character delimiter) throws IOException {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(store.read(path, delimiter));
    }
    @PutMapping public CsvStore.Document save(@RequestParam String path, @RequestBody SaveRequest request) throws IOException {
        return store.save(path, request.table(), request.revision());
    }
}
