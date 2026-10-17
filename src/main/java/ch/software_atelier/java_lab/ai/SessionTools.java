package ch.software_atelier.java_lab.ai;

import ch.software_atelier.java_lab.KernelService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Binds tool execution to the kernel selected by the browser, never to a model-supplied kernel ID. */
@Component
public class SessionTools {
    private final KernelService kernels;
    public SessionTools(KernelService kernels) { this.kernels = kernels; }

    private static final Map<String,Object> TEXT = Map.of("type", "string", "maxLength", 300);
    private static final Map<String,Object> OFFSET = Map.of("type", "integer", "minimum", 0, "maximum", 1000000);
    private static final List<Map<String,Object>> DEFINITIONS = List.of(
        definition("session_overview", "List current session variables/types, active imports, methods, classes, and loaded libraries with Maven coordinates. Up to 50 entries per category with totals; paginate with offset and limit. Does not evaluate variable toString or getters.",
            Map.of("offset", OFFSET, "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50)), List.of()),
        definition("inspect_variable", "Read a current variable or a nested value. Use the selectors returned in children as path steps. Supports private/inherited fields, array/list/collection indexes, map entry indexes (value by default; key:true reads its key). Pages contain up to 25 children. Cycles and inaccessible fields are marked. No arbitrary expressions or getters.",
            Map.of("variable", TEXT, "path", Map.of("type", "array", "maxItems", 16, "items", Map.of("type", "object", "properties",
                Map.of("field", TEXT, "declaringClass", TEXT, "index", OFFSET, "entry", OFFSET, "key", Map.of("type", "boolean")), "additionalProperties", false)),
                "offset", OFFSET, "limit", Map.of("type", "integer", "minimum", 1, "maximum", 25)), List.of("variable")),
        definition("search_library_classes", "Search fully qualified class names in the actual loaded library JARs by case-insensitive substring. Returns up to 40 matches and their library; paginate using offset. For JDK classes use inspect_class directly.",
            Map.of("query", TEXT, "offset", OFFSET), List.of("query")),
        definition("inspect_class", "List public constructors, methods (including inherited methods), and fields of a loaded class, without initializing it. Use fully qualified binary library/JDK class names (nested classes use $), or a simple session-defined type name from session_overview. Returns up to 40 API signatures; filter by method name and paginate with offset. Signatures are not Javadoc descriptions.",
            Map.of("className", TEXT, "filter", TEXT, "offset", OFFSET), List.of("className"))
    );

    private static Map<String,Object> definition(String name, String description, Map<String,Object> properties, List<String> required) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
            "parameters", Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false)));
    }

    public CodeGeneration.Tools forKernel(String kernelId) {
        return new CodeGeneration.Tools() {
            public List<Map<String,Object>> definitions() { return DEFINITIONS; }
            public Object execute(String name, JsonNode args) {
                if (!Set.of("session_overview", "inspect_variable", "search_library_classes", "inspect_class").contains(name))
                    return Map.of("error", "Unknown tool");
                if (args == null || !args.isObject() || args.toString().length() > 8000) return Map.of("error", "Invalid tool arguments");
                for (String key : List.of("variable", "className", "query", "filter"))
                    if (args.has(key) && (!args.get(key).isTextual() || args.get(key).asText().length() > 300)) return Map.of("error", "Invalid text argument");
                if ((name.equals("inspect_variable") && !args.hasNonNull("variable")) || (name.equals("inspect_class") && !args.hasNonNull("className")))
                    return Map.of("error", "Missing variable or class name");
                if (args.has("path") && (!args.get("path").isArray() || args.get("path").size() > 16)) return Map.of("error", "Invalid path");
                for (String key : List.of("offset", "limit"))
                    if (args.has(key) && (!args.get(key).isIntegralNumber() || !args.get(key).canConvertToInt() || args.get(key).asInt() < 0 || args.get(key).asInt() > 1000000))
                        return Map.of("error", "Invalid pagination");
                return kernels.inspectSession(kernelId, name, args);
            }
        };
    }
}
