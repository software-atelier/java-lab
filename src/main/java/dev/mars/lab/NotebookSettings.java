package dev.mars.lab;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.regex.Pattern;

final class NotebookSettings {
    record Variable(String name, String defaultValue, String env, boolean secret) {}
    record Settings(List<Variable> variables, List<String> dependencies) {}
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern GAV = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}:[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}:[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");

    static Settings parse(JsonNode notebook) {
        JsonNode metadata = notebook.path("metadata");
        JsonNode lab = metadata.path("java_lab");
        if (lab.isMissingNode()) return new Settings(List.of(), List.of());
        if (!lab.isObject()) throw invalid("java_lab metadata must be an object");
        JsonNode variables = lab.path("variables"), dependencies = lab.path("dependencies");
        if (!variables.isMissingNode() && (!variables.isArray() || variables.size() > 64)) throw invalid("variables must be an array of at most 64 entries");
        if (!dependencies.isMissingNode() && (!dependencies.isArray() || dependencies.size() > 20)) throw invalid("dependencies must be an array of at most 20 entries");
        List<Variable> vars = new ArrayList<>(); Set<String> names = new HashSet<>();
        if (variables.isArray()) for (JsonNode item : variables) {
            if (!item.isObject()) throw invalid("variable must be an object");
            Iterator<String> keys = item.fieldNames();
            while (keys.hasNext()) if (!Set.of("name", "defaultValue", "env", "secret").contains(keys.next())) throw invalid("Unsupported variable field; values cannot be saved in notebooks");
            String name = text(item, "name", true, 64);
            if (!NAME.matcher(name).matches() || !names.add(name)) throw invalid("Invalid or duplicate variable name");
            String def = text(item, "defaultValue", false, 4096), env = text(item, "env", false, 64);
            if (env != null && !NAME.matcher(env).matches()) throw invalid("Invalid environment variable name");
            JsonNode secretNode = item.path("secret");
            if (!secretNode.isMissingNode() && !secretNode.isBoolean()) throw invalid("secret must be boolean");
            boolean secret = secretNode.asBoolean(false);
            if (secret && def != null) throw invalid("Secret variables cannot have document defaults");
            vars.add(new Variable(name, def, env, secret));
        }
        List<String> deps = new ArrayList<>(); Set<String> seen = new HashSet<>();
        if (dependencies.isArray()) for (JsonNode item : dependencies) {
            if (!item.isTextual() || !GAV.matcher(item.textValue()).matches() || !seen.add(item.textValue())) throw invalid("Dependencies must be unique Maven groupId:artifactId:version coordinates");
            deps.add(item.textValue());
        }
        return new Settings(List.copyOf(vars), List.copyOf(deps));
    }
    private static String text(JsonNode item, String key, boolean required, int limit) {
        JsonNode value = item.path(key);
        if (value.isMissingNode()) { if (required) throw invalid(key + " is required"); return null; }
        if (!value.isTextual() || value.textValue().length() > limit) throw invalid("Invalid " + key);
        return value.textValue();
    }
    static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
