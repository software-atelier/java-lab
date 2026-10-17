package ch.software_atelier.java_lab;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final TextFileStore text;
    private final NotebookStore files;
    public FileController(TextFileStore text, NotebookStore files) { this.text = text;this.files = files; }
    public record SaveRequest(String content, boolean bom, String recordSeparator, String revision) {}
    public record CreateRequest(String folder,String name,String extension) {}
    public record RenameRequest(String path,String name) {}
    public record XmlRequest(String content) {}
    @GetMapping("/info") public ResponseEntity<NotebookStore.FileEntry> info(@RequestParam String path) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.fileInfo(path));}
    @PostMapping public NotebookStore.FileEntry create(@RequestBody CreateRequest request) throws IOException {return files.createFile(request.folder(),request.name(),request.extension());}
    @PatchMapping("/rename") public NotebookStore.FileEntry rename(@RequestBody RenameRequest request) throws IOException {return files.renameFile(request.path(),request.name());}
    @PostMapping("/validate-xml") public TextFileStore.Validation validateXml(@RequestBody XmlRequest request) {return text.validateXml(request.content());}
    @GetMapping("/text") public ResponseEntity<TextFileStore.Document> read(@RequestParam String path) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(text.read(path));
    }
    @PutMapping("/text") public TextFileStore.Document save(@RequestParam String path, @RequestBody SaveRequest request) throws IOException {
        return text.save(path, request.content(), request.bom(), request.recordSeparator(), request.revision());
    }
    @GetMapping("/download") public ResponseEntity<Resource> download(@RequestParam String path) {
        var file = files.existingFile(path);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(file));
    }
}
