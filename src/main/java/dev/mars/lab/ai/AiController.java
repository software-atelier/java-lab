package dev.mars.lab.ai;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final CodeGenerationService service;
    public AiController(CodeGenerationService service) { this.service = service; }
    @GetMapping("/config") public CodeGenerationService.Configuration configuration() { return service.configuration(); }
    @PostMapping("/generate") public CodeGeneration.Result generate(@RequestBody CodeGeneration.Request request) { return service.generate(request); }
}
