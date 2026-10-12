package dev.mars.lab;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/kernels")
public class KernelController {
    private final KernelService kernels;
    public KernelController(KernelService kernels) { this.kernels = kernels; }
    public record ExecuteRequest(String code) {}
    public record CompletionRequest(String code, int cursor) {}
    @PostMapping public Map<String, String> create() { return Map.of("id", kernels.create()); }
    @PostMapping("/{id}/execute") public KernelService.Result execute(@PathVariable String id, @RequestBody ExecuteRequest request) { return kernels.execute(id, request.code()); }
    @PostMapping("/{id}/complete") public KernelService.Completions complete(@PathVariable String id, @RequestBody CompletionRequest request) { return kernels.complete(id, request.code(), request.cursor()); }
    @PostMapping("/{id}/inspect") public KernelService.Inspection inspect(@PathVariable String id, @RequestBody CompletionRequest request) { return kernels.inspect(id, request.code(), request.cursor()); }
    @PostMapping("/{id}/interrupt") public void interrupt(@PathVariable String id) { kernels.interrupt(id); }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) { kernels.remove(id); }
}
