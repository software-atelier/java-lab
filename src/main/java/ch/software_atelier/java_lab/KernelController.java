package ch.software_atelier.java_lab;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/kernels")
public class KernelController {
    private final KernelService kernels;
    public KernelController(KernelService kernels) { this.kernels = kernels; }
    public record CreateRequest(String notebook) {}
    public record ExecuteRequest(String code, Map<String, Object> formValues) {}
    public record CompletionRequest(String code, int cursor) {}
    @PostMapping public Map<String, String> create(@RequestBody(required = false) CreateRequest request) throws java.io.IOException { return Map.of("id", kernels.create(request == null ? null : request.notebook())); }
    @PostMapping("/{id}/execute") public KernelService.Result execute(@PathVariable String id, @RequestBody ExecuteRequest request) { return kernels.execute(id, request.code(), request.formValues()); }
    @PostMapping("/{id}/complete") public KernelService.Completions complete(@PathVariable String id, @RequestBody CompletionRequest request) { return kernels.complete(id, request.code(), request.cursor()); }
    @PostMapping("/{id}/inspect") public KernelService.Inspection inspect(@PathVariable String id, @RequestBody CompletionRequest request) { return kernels.inspect(id, request.code(), request.cursor()); }
    @PostMapping("/{id}/interrupt") public void interrupt(@PathVariable String id) { kernels.interrupt(id); }
    @GetMapping("/{id}/resources") public KernelService.Resources resources(@PathVariable String id) { return kernels.resources(id); }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) { kernels.remove(id); }
}
