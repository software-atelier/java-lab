package ch.software_atelier.java_lab.display;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Kernel-local form values, synchronized from the notebook before each execution. */
public final class Forms {
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");
    private static final Map<String, Object> VALUES = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> RULES = new LinkedHashMap<>();
    private Forms() {}

    public static Form form(String id) { return new Form(id); }
    public static synchronized Object value(String id) { return VALUES.get(checkId(id)); }
    public static synchronized String text(String id) { Object value = value(id); return value == null ? null : String.valueOf(value); }
    public static synchronized List<String> list(String id) {
        Object value = value(id);
        if (value == null) return List.of();
        if (!(value instanceof List<?> items) || items.stream().anyMatch(item -> !(item instanceof String)))
            throw new IllegalStateException("Field is not a multiselect: " + id);
        return items.stream().map(String.class::cast).toList();
    }
    public static synchronized boolean bool(String id) { Object value = value(id); return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)); }
    public static synchronized double number(String id) {
        Object value = value(id);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("No numeric value for field " + id);
        return Double.parseDouble(String.valueOf(value));
    }

    /** Host-side bridge. Base64 avoids source-string escaping in JShell. */
    public static synchronized void loadBase64(String encoded) {
        String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        JSONObject object = new JSONObject(json);
        if (object.length() > 200) throw new IllegalArgumentException("Too many form values");
        Map<String, Object> next = new LinkedHashMap<>();
        for (String id : object.keySet()) {
            Object value = object.get(id);
            checkId(id);
            if (!(value instanceof String || value instanceof Boolean || value instanceof Number || value instanceof org.json.JSONArray || value == JSONObject.NULL))
                throw new IllegalArgumentException("Unsupported form value for " + id);
            Object normalized = value instanceof org.json.JSONArray array ? array.toList() : value == JSONObject.NULL ? null : value;
            if (normalized instanceof List<?> items && (items.size() > 100 || items.stream().anyMatch(item -> !(item instanceof String))))
                throw new IllegalArgumentException("Invalid multiselect value for " + id);
            validateValue(id, normalized, RULES.get(id));
            next.put(id, normalized);
        }
        for (var entry : RULES.entrySet())
            if (Boolean.TRUE.equals(entry.getValue().get("required")) && !next.containsKey(entry.getKey()))
                throw new IllegalArgumentException("Required field: " + entry.getKey());
        VALUES.clear();
        VALUES.putAll(next);
    }

    private static void validateValue(String id, Object value, Map<String, Object> rule) {
        if (rule == null) return;
        String type = (String) rule.get("type");
        boolean empty = value == null || value instanceof String text && text.isBlank() || value instanceof List<?> list && list.isEmpty();
        if (Boolean.TRUE.equals(rule.get("required")) && (empty || value instanceof Boolean b && !b))
            throw new IllegalArgumentException("Required field: " + id);
        if (empty) return;
        if ("multiselect".equals(type)) {
            if (!(value instanceof List<?> selected) || !((List<?>) rule.get("options")).containsAll(selected))
                throw new IllegalArgumentException("Invalid selection: " + id);
        } else if ("select".equals(type)) {
            if (!(value instanceof String) || !((List<?>) rule.get("options")).contains(value)) throw new IllegalArgumentException("Invalid selection: " + id);
        } else if ("number".equals(type)) {
            double number;
            try { number = Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number: " + id); }
            if (!Double.isFinite(number) || rule.containsKey("min") && number < ((Number) rule.get("min")).doubleValue()
                || rule.containsKey("max") && number > ((Number) rule.get("max")).doubleValue()) throw new IllegalArgumentException("Number out of range: " + id);
        } else if ("email".equals(type)) {
            if (!(value instanceof String text) || !text.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Invalid email: " + id);
        } else if ("date".equals(type)) {
            try { java.time.LocalDate.parse(String.valueOf(value)); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid date: " + id); }
        } else if ("checkbox".equals(type) && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid checkbox: " + id);
        else if (("text".equals(type) || "textarea".equals(type)) && !(value instanceof String)) throw new IllegalArgumentException("Invalid text: " + id);
    }

    private static String checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Field and form ids must start with a letter and contain letters, digits, _, . or - (up to 100 characters)");
        return id;
    }

    public static final class Form {
        private final String id;
        private String title = "Form";
        private final List<Map<String, Object>> fields = new ArrayList<>();
        private Form(String id) { this.id = checkId(id); }
        public Form title(String title) { this.title = Objects.requireNonNull(title); return this; }
        public Form text(String id, String label) { return text(id, label, ""); }
        public Form text(String id, String label, String initial) { return field(id, label, "text", Objects.requireNonNull(initial), null); }
        public Form textarea(String id, String label) { return textarea(id, label, ""); }
        public Form textarea(String id, String label, String initial) { return field(id, label, "textarea", Objects.requireNonNull(initial), null); }
        public Form email(String id, String label) { return field(id, label, "email", "", null); }
        public Form date(String id, String label) { return field(id, label, "date", "", null); }
        public Form number(String id, String label, double initial) {
            if (!Double.isFinite(initial)) throw new IllegalArgumentException("Numeric form values must be finite");
            return field(id, label, "number", initial, null);
        }
        public Form checkbox(String id, String label) { return checkbox(id, label, false); }
        public Form checkbox(String id, String label, boolean initial) { return field(id, label, "checkbox", initial, null); }
        public Form select(String id, String label, String... options) {
            validateOptions(options);
            return field(id, label, "select", Objects.requireNonNull(options[0]), List.of(options));
        }
        public Form multiselect(String id, String label, String... options) {
            validateOptions(options);
            return field(id, label, "multiselect", List.of(), List.of(options));
        }
        public Form required(String id) {
            Map<String, Object> field = find(id);
            field.put("required", true);
            synchronized (Forms.class) { RULES.put(id, new LinkedHashMap<>(field)); }
            return this;
        }
        public Form range(String id, double min, double max) {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) throw new IllegalArgumentException("Invalid number range");
            Map<String, Object> field = find(id);
            if (!"number".equals(field.get("type"))) throw new IllegalArgumentException("Range requires numeric field");
            field.put("min", min); field.put("max", max);
            synchronized (Forms.class) { RULES.put(id, new LinkedHashMap<>(field)); }
            return this;
        }
        private Map<String, Object> find(String id) {
            return fields.stream().filter(field -> field.get("id").equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown form field: " + id));
        }
        private static void validateOptions(String[] options) {
            if (options == null || options.length == 0 || options.length > 100 || java.util.Arrays.stream(options).anyMatch(Objects::isNull)
                || java.util.Arrays.stream(options).anyMatch(String::isBlank) || java.util.Arrays.stream(options).distinct().count() != options.length)
                throw new IllegalArgumentException("Select requires 1–100 distinct non-empty options");
        }
        private Form field(String id, String label, String type, Object initial, List<String> options) {
            checkId(id); Objects.requireNonNull(label);
            if (label.length() > 200 || title.length() > 200) throw new IllegalArgumentException("Form label or title is too long");
            if (fields.size() >= 100 || fields.stream().anyMatch(f -> f.get("id").equals(id))) throw new IllegalArgumentException("Too many or duplicate form fields");
            synchronized (Forms.class) { VALUES.putIfAbsent(id, initial); }
            var field = new LinkedHashMap<String, Object>();
            field.put("id", id); field.put("label", label); field.put("type", type);
            synchronized (Forms.class) { field.put("value", VALUES.get(id)); }
            if (options != null) field.put("options", options);
            fields.add(field);
            synchronized (Forms.class) { RULES.put(id, new LinkedHashMap<>(field)); }
            return this;
        }
        public void show() {
            if (fields.isEmpty()) throw new IllegalStateException("Form needs at least one field");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("id", id); payload.put("title", title); payload.put("fields", fields);
            byte[] bytes = JsonOptions.encode(payload).getBytes(StandardCharsets.UTF_8);
            Display.emit("application/vnd.java-lab.form+json", title, 900, 320, bytes);
        }
    }
}
