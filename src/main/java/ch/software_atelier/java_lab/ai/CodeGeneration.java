package ch.software_atelier.java_lab.ai;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Provider-neutral contract. Context is separate from the edit instruction and target. */
public final class CodeGeneration {
    private CodeGeneration() {}
    public record Target(String source, Integer start, Integer end) {}
    public record ContextItem(String kind, String title, String content, Map<String, Object> metadata) {}
    public record Request(String prompt, Target target, List<ContextItem> context, String kernelId, String requestId, String language) {
        public Request(String prompt, Target target, List<ContextItem> context, String kernelId, String requestId) { this(prompt, target, context, kernelId, requestId, null); }
        public Request(String prompt, Target target, List<ContextItem> context) { this(prompt, target, context, null, null); }
        public Request(String prompt, Target target, List<ContextItem> context, String kernelId) { this(prompt, target, context, kernelId, null); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String role, String content, @JsonProperty("tool_calls") List<ToolCall> toolCalls,
                          @JsonProperty("tool_call_id") String toolCallId) {
        public Message(String role, String content) { this(role, content, null, null); }
    }
    public record Function(String name, String arguments) {}
    public record ToolCall(String id, String type, Function function) {}
    public interface Tools {
        List<Map<String,Object>> definitions();
        Object execute(String name, JsonNode arguments);
    }
    public record ResponseDiagnostics(int request, String configuredModel, String reportedModel, int maxTokens,
                                      String finishReason, Long inputTokens, Long outputTokens, Long reasoningTokens) {}
    public interface Observer extends java.util.function.Consumer<String> {
        default void response(ResponseDiagnostics diagnostics) {}
    }
    public record Result(String code, String model, List<ResponseDiagnostics> diagnostics) {
        public Result(String code, String model) { this(code, model, List.of()); }
    }
}
