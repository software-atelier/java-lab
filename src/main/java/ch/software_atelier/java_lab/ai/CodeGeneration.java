package ch.software_atelier.java_lab.ai;

import java.util.List;
import java.util.Map;

/** Provider-neutral contract. Context is separate from the edit instruction and target. */
public final class CodeGeneration {
    private CodeGeneration() {}
    public record Target(String source, Integer start, Integer end) {}
    public record ContextItem(String kind, String title, String content, Map<String, Object> metadata) {}
    public record Request(String prompt, Target target, List<ContextItem> context) {}
    public record Message(String role, String content) {}
    public record Result(String code, String model) {}
}
