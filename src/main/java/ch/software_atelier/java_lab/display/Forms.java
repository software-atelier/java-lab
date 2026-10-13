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
    private Forms() {}

    public static Form form(String id) { return new Form(id); }
    public static synchronized Object value(String id) { return VALUES.get(checkId(id)); }
    public static synchronized String text(String id) { Object value = value(id); return value == null ? null : String.valueOf(value); }
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
        VALUES.clear();
        for (String id : object.keySet()) {
            Object value = object.get(id);
            checkId(id);
            if (!(value instanceof String || value instanceof Boolean || value instanceof Number || value == JSONObject.NULL))
                throw new IllegalArgumentException("Unsupported form value for " + id);
            VALUES.put(id, value == JSONObject.NULL ? null : value);
        }
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
        public Form number(String id, String label, double initial) {
            if (!Double.isFinite(initial)) throw new IllegalArgumentException("Numeric form values must be finite");
            return field(id, label, "number", initial, null);
        }
        public Form checkbox(String id, String label) { return checkbox(id, label, false); }
        public Form checkbox(String id, String label, boolean initial) { return field(id, label, "checkbox", initial, null); }
        public Form select(String id, String label, String... options) {
            if (options == null || options.length == 0 || options.length > 100) throw new IllegalArgumentException("Select requires 1–100 options");
            return field(id, label, "select", Objects.requireNonNull(options[0]), List.of(options));
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
