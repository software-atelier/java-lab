package ch.software_atelier.java_lab.display;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Display an Apache ECharts option object as an interactive notebook chart. */
public final class ECharts {
    private ECharts() {}
    public static Chart chart() { return new Chart(); }
    public static Series barSeries(String name, Number... values) { return new Series("bar", name, Arrays.asList(values)); }
    public static Series barSeries(String name, double[] values) { return new Series("bar", name, values.clone()); }
    public static Series barSeries(String name, List<? extends Number> values) { return new Series("bar", name, new ArrayList<>(values)); }
    public static Series lineSeries(String name, Number... values) { return new Series("line", name, Arrays.asList(values)); }
    public static Series lineSeries(String name, double[] values) { return new Series("line", name, values.clone()); }
    public static Series lineSeries(String name, List<? extends Number> values) { return new Series("line", name, new ArrayList<>(values)); }
    public static Series scatterSeries(String name, double[][] points) { return new Series("scatter", name, points); }
    public static Series pieSeries(String name, Map<String, ? extends Number> values) {
        var data = new ArrayList<Map<String, Object>>();
        values.forEach((label, value) -> data.add(Map.of("name", label, "value", value)));
        return new Series("pie", name, data);
    }
    public static void show(Chart chart) { Objects.requireNonNull(chart, "Chart is required").show(); }
    public static void show(Chart chart, int width, int height) { show(chart.toJson(), width, height); }
    public static void show(String optionJson) { show(optionJson, 900, 520); }
    public static void show(String optionJson, int width, int height) {
        Objects.requireNonNull(optionJson, "ECharts option JSON is required");
        Display.validateSize(width, height);
        String json = optionJson.strip();
        if (json.length() > 1_000_000 || !json.startsWith("{") || !json.endsWith("}"))
            throw new IllegalArgumentException("ECharts options must be a JSON object of at most 1,000,000 characters");
        Display.emit("application/vnd.apache.echarts+json", "Apache ECharts", width, height, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Fluent Java option builder. Serialization happens automatically at display time. */
    public static final class Chart {
        private final Map<String, Object> options = new LinkedHashMap<>();
        private final List<Series> series = new ArrayList<>();
        private int width = 900, height = 520;

        private Chart() {
            options.put("tooltip", Map.of("trigger", "axis"));
            options.put("legend", Map.of("show", true, "top", 35));
            options.put("grid", Map.of("top", 90, "left", 55, "right", 25, "bottom", 45));
        }
        public Chart title(String text) { section("title").put("text", text);return this; }
        public Chart subtitle(String text) { section("title").put("subtext", text);return this; }
        public Chart categories(String... labels) { return categories(Arrays.asList(labels)); }
        public Chart categories(List<String> labels) {
            section("xAxis").put("type", "category");section("xAxis").put("data", new ArrayList<>(labels));return this;
        }
        public Chart xAxis(String name) { section("xAxis").put("name", name);return this; }
        public Chart yAxis(String name) { section("yAxis").put("name", name);return this; }
        public Chart xAxisValue(String name) { section("xAxis").put("type", "value");return xAxis(name); }
        public Chart legend(boolean visible) { section("legend").put("show", visible);return this; }
        public Chart tooltip(boolean visible) { section("tooltip").put("show", visible);return this; }
        public Chart tooltipAxis() { section("tooltip").put("trigger", "axis");return this; }
        public Chart tooltipItem() { section("tooltip").put("trigger", "item");return this; }
        public Chart zoom() {
            options.put("dataZoom", List.of(Map.of("type", "inside"), Map.of("type", "slider")));
            section("grid").put("bottom", 85);return this;
        }
        public Chart size(int width, int height) { Display.validateSize(width, height);this.width=width;this.height=height;return this; }
        public Chart bar(String name, Number... values) { return series(barSeries(name, values)); }
        public Chart bar(String name, double[] values) { return series(barSeries(name, values)); }
        public Chart bar(String name, List<? extends Number> values) { return series(barSeries(name, values)); }
        public Chart line(String name, Number... values) { return series(lineSeries(name, values)); }
        public Chart line(String name, double[] values) { return series(lineSeries(name, values)); }
        public Chart line(String name, List<? extends Number> values) { return series(lineSeries(name, values)); }
        public Chart scatter(String name, double[][] points) { return series(scatterSeries(name, points)); }
        public Chart pie(String name, Map<String, ? extends Number> values) { return series(pieSeries(name, values)); }
        public Chart series(Series value) {
            Objects.requireNonNull(value, "Series is required");series.add(value);
            String type = (String) value.options.get("type");
            if (!type.equals("pie")) {
                section("xAxis").putIfAbsent("type", type.equals("scatter") ? "value" : "category");
                section("yAxis").putIfAbsent("type", "value");
            } else if (series.size() == 1) tooltipItem();
            return this;
        }
        /** Escape hatch for ordinary Java Maps/Lists/arrays when a convenience method is insufficient. */
        public Chart option(String key, Object value) { options.put(Objects.requireNonNull(key), value);return this; }
        public Map<String, Object> toOption() {
            var result = new LinkedHashMap<>(options);
            if (!series.isEmpty()) result.put("series", series.stream().map(Series::toOption).toList());
            return result;
        }
        public String toJson() { return JsonOptions.encode(toOption()); }
        public void show() { ECharts.show(toJson(), width, height); }
        public void show(int width, int height) { ECharts.show(toJson(), width, height); }
        @Override public String toString() { return "ECharts chart (" + series.size() + " series)"; }

        @SuppressWarnings("unchecked")
        private Map<String, Object> section(String name) {
            Object current = options.get(name);
            var value = new LinkedHashMap<String, Object>();
            if (current instanceof Map<?, ?>) value.putAll((Map<String, Object>) current);
            options.put(name, value);return value;
        }
    }

    public static final class Series {
        private final Map<String, Object> options = new LinkedHashMap<>();
        private Series(String type, String name, Object data) {
            options.put("type", type);options.put("name", Objects.requireNonNull(name));options.put("data", data);
        }
        public Series smooth() { return smooth(true); }
        public Series smooth(boolean enabled) { options.put("smooth", enabled);return this; }
        public Series stack(String name) { options.put("stack", name);return this; }
        public Series color(String color) { options.put("itemStyle", Map.of("color", color));return this; }
        public Series labels() { options.put("label", Map.of("show", true));return this; }
        public Series area() { options.put("areaStyle", Map.of());return this; }
        public Series option(String key, Object value) { options.put(Objects.requireNonNull(key), value);return this; }
        public Map<String, Object> toOption() { return new LinkedHashMap<>(options); }
    }

}
