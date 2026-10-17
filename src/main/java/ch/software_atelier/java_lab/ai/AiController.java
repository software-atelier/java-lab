package ch.software_atelier.java_lab.ai;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final CodeGenerationService service;
    public AiController(CodeGenerationService service) { this.service = service; }
    @GetMapping("/config") public CodeGenerationService.Configuration configuration() { return service.configuration(); }
    @PostMapping("/generate") public CodeGeneration.Result generate(@RequestBody CodeGeneration.Request request) { return service.generate(request); }
    @GetMapping("/progress/{requestId}") public org.springframework.http.ResponseEntity<GenerationProgress.Snapshot> progress(@PathVariable String requestId) {
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.progress(requestId));
    }
}
