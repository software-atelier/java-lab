package ch.software_atelier.java_lab.display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs inside the notebook JVM. Never evaluates supplied expressions or calls object getters/toString. */
public final class SessionInspector {
    private SessionInspector() {}

    public static String inspect(Object root, String encodedRequest) {
        JSONObject result;
        try {
            var request = new JSONObject(new String(Base64.getDecoder().decode(encodedRequest), StandardCharsets.UTF_8));
            var secrets = request.optJSONArray("__secretValues");
            var path = request.optJSONArray("path");
            if (path == null) path = new JSONArray();
            if (path.length() > 16) throw new IllegalArgumentException("Path exceeds 16 steps");
            Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
            Object node = root;
            for (int i = 0; i < path.length(); i++) {
                if (node == null) throw new IllegalArgumentException("Cannot descend into null");
                ancestors.add(node);
                node = descend(node, path.getJSONObject(i));
            }
            result = describe(node, ancestors, secrets);
            if (node != null && !scalar(node) && !ancestors.contains(node)) {
                ancestors.add(node);
                int offset = Math.max(0, request.optInt("offset", 0));
                int limit = Math.max(1, Math.min(25, request.optInt("limit", 20)));
                var children = new JSONArray();
                int total;
                if (node.getClass().isArray()) {
                    total = Array.getLength(node);
                    for (int i = offset; i < total && children.length() < limit; i++)
                        children.put(child(Array.get(node, i), ancestors, new JSONObject().put("index", i), secrets));
                } else if (node instanceof List<?> list) {
                    total = list.size();
                    for (int i = offset; i < total && children.length() < limit; i++)
                        children.put(child(list.get(i), ancestors, new JSONObject().put("index", i), secrets));
                } else if (node instanceof Map<?, ?> map) {
                    total = map.size();
                    int i = 0;
                    for (var entry : map.entrySet()) {
                        if (i >= offset && children.length() < limit)
                            children.put(child(entry.getValue(), ancestors, new JSONObject().put("entry", i), secrets)
                                .put("key", describe(entry.getKey(), ancestors, secrets)));
                        if (++i >= (long) offset + limit) break;
                    }
                } else if (node instanceof Collection<?> collection) {
                    total = collection.size();
                    int i = 0;
                    for (Object value : collection) {
                        if (i >= offset && children.length() < limit)
                            children.put(child(value, ancestors, new JSONObject().put("index", i), secrets));
                        if (++i >= (long) offset + limit) break;
                    }
                } else {
                    var fields = fields(node.getClass());
                    total = fields.size();
                    for (int i = offset; i < total && children.length() < limit; i++) {
                        Field field = fields.get(i);
                        var selector = new JSONObject().put("field", field.getName()).put("declaringClass", field.getDeclaringClass().getName());
                        try { children.put(child(read(field, node), ancestors, selector, secrets)); }
                        catch (ReflectiveOperationException | RuntimeException e) {
                            children.put(new JSONObject().put("selector", selector).put("type", field.getType().getTypeName()).put("unavailable", "Field is not accessible"));
                        }
                    }
                }
                result.put("children", children).put("total", total).put("offset", offset)
                    .put("hasMore", (long) offset + children.length() < total);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            result = new JSONObject().put("error", "Cannot inspect the requested path (missing, inaccessible, or invalid selector)");
        }
        return Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject child(Object value, Set<Object> ancestors, JSONObject selector, JSONArray secrets) {
        return describe(value, ancestors, secrets).put("selector", selector);
    }

    private static JSONObject describe(Object value, Set<Object> ancestors, JSONArray secrets) {
        var result = new JSONObject();
        if (value == null) return result.put("value", JSONObject.NULL).put("type", "null");
        result.put("type", value.getClass().getTypeName());
        if (scalar(value)) {
            String text = value instanceof Enum<?> e ? e.name() : String.valueOf(value);
            if (secrets != null) for (int i = 0; i < secrets.length(); i++) {
                String secret = secrets.getString(i);
                if (!secret.isEmpty()) text = text.replace(secret, "[REDACTED]");
            }
            result.put("value", text.substring(0, Math.min(1000, text.length()))).put("truncated", text.length() > 1000);
        } else {
            result.put("identity", Integer.toHexString(System.identityHashCode(value)));
            if (ancestors.contains(value)) result.put("cycle", true);
        }
        return result;
    }

    private static boolean scalar(Object value) {
        Class<?> type = value.getClass();
        return type == String.class || type == Boolean.class || type == Character.class || type == Byte.class
            || type == Short.class || type == Integer.class || type == Long.class || type == Float.class
            || type == Double.class || type == java.math.BigDecimal.class || type == java.math.BigInteger.class || value instanceof Enum<?>;
    }

    private static Object descend(Object node, JSONObject step) throws ReflectiveOperationException {
        if (step.has("field")) {
            for (Field field : fields(node.getClass()))
                if (field.getName().equals(step.getString("field"))
                    && (!step.has("declaringClass") || field.getDeclaringClass().getName().equals(step.getString("declaringClass"))))
                    return read(field, node);
        } else if (step.has("index")) {
            int index = step.getInt("index");
            if (node.getClass().isArray()) return Array.get(node, index);
            if (node instanceof List<?> list) return list.get(index);
            if (node instanceof Collection<?> collection) {
                if (index < 0 || index >= collection.size()) throw new IllegalArgumentException();
                var iterator = collection.iterator();
                for (int i = 0; i < index; i++) iterator.next();
                return iterator.next();
            }
        } else if (step.has("entry") && node instanceof Map<?, ?> map) {
            int index = step.getInt("entry");
            if (index < 0 || index >= map.size()) throw new IllegalArgumentException();
            var iterator = map.entrySet().iterator();
            for (int i = 0; i < index; i++) iterator.next();
            var entry = iterator.next();
            return step.optBoolean("key", false) ? entry.getKey() : entry.getValue();
        }
        throw new IllegalArgumentException("Invalid selector");
    }

    private static Object read(Field field, Object node) throws IllegalAccessException {
        if (!field.trySetAccessible()) throw new IllegalAccessException();
        return field.get(node);
    }

    private static List<Field> fields(Class<?> type) {
        var result = new ArrayList<Field>();
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            for (Field field : current.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) result.add(field);
        result.sort(Comparator.comparing(Field::getName).thenComparing(f -> f.getDeclaringClass().getName()));
        return result;
    }

    /** Public API signatures only; loading does not initialize the inspected class. */
    public static String api(String encodedRequest) {
        return api(null, encodedRequest);
    }

    public static String api(Class<?> sessionType, String encodedRequest) {
        JSONObject result;
        try {
            var request = new JSONObject(new String(Base64.getDecoder().decode(encodedRequest), StandardCharsets.UTF_8));
            Class<?> type = sessionType != null ? sessionType
                : Class.forName(request.getString("className"), false, SessionInspector.class.getClassLoader());
            String filter = request.optString("filter", "").toLowerCase(Locale.ROOT);
            var signatures = new TreeSet<String>();
            for (var c : type.getConstructors()) signatures.add(c.toGenericString());
            for (var m : type.getMethods()) if (!m.isSynthetic() && !m.isBridge()) signatures.add(m.toGenericString());
            for (var f : type.getFields()) signatures.add(f.toGenericString());
            var matched = signatures.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(filter)).toList();
            int offset = Math.max(0, request.optInt("offset", 0));
            var page = matched.stream().skip(offset).limit(40).map(s -> s.substring(0, Math.min(s.length(), 2000))).toList();
            result = new JSONObject().put("className", type.getName()).put("signatures", page).put("total", matched.size())
                .put("hasMore", (long) offset + page.size() < matched.size());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            result = new JSONObject().put("error", "Class API is unavailable; use a fully qualified class name from library search");
        }
        return Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8));
    }
}
