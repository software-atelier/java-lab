package ch.software_atelier.java_lab.ai;

import java.util.List;

/** Transport boundary: additional model providers can implement this contract. */
public interface CodeGenerationProvider {
    String name();
    boolean configured();
    String model();
    CodeGeneration.Result generate(List<CodeGeneration.Message> messages);
    default CodeGeneration.Result generateText(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools,
                                               CodeGeneration.Observer observer) {
        return generate(messages, tools, observer);
    }
    default CodeGeneration.Result generate(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools) {
        return generate(messages);
    }
    default CodeGeneration.Result generate(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools, java.util.function.Consumer<String> progress) {
        progress.accept("Waiting for the AI model…");
        return tools == null ? generate(messages) : generate(messages, tools);
    }
    default CodeGeneration.Result generate(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools,
                                          CodeGeneration.Observer observer, CodeGeneration.Target target) {
        var result = generate(messages, tools, observer);
        if (JavaSnippetValidator.problem(target, result.code()) != null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "The model returned incomplete or malformed Java. Your original cell was kept.");
        return result;
    }
}
