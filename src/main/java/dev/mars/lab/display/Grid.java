package dev.mars.lab.display;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fluent Java wrapper for local, interactive Grid.js notebook tables. */
public final class Grid {
    private Grid() {}
    public static Table table() { return new Table(); }
    public static Column column(String name) { return new Column(name); }
    public static Table fromRecords(List<? extends Map<String, ?>> records) { return table().records(records); }
    public static void show(Table table) { Objects.requireNonNull(table, "Table is required").show(); }

    public static final class Table {
        private final List<Column> columns = new ArrayList<>();
        private final List<List<Object>> rows = new ArrayList<>();
        private final Map<String, Object> options = new LinkedHashMap<>();
        private String title = "Grid.js table";
        private int width = 1000;

        private Table() { options.put("sort", true);options.put("search", true);options.put("pagination", Map.of("limit", 10)); }
        public Table title(String title) { this.title=Objects.requireNonNull(title);return this; }
        public Table columns(String... names) { for(String name:names)columns.add(Grid.column(name));return this; }
        public Table columns(List<String> names) { return columns(names.toArray(String[]::new)); }
        public Table columns(Column... values) { columns.addAll(Arrays.asList(values));return this; }
        public Table row(Object... values) { return row(Arrays.asList(values)); }
        public Table row(List<?> values) {
            if(rows.size()>=10_000)throw new IllegalArgumentException("Tables are limited to 10,000 rows");
            var row=new ArrayList<Object>();
            for(Object value:values) {
                if(value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                    throw new IllegalArgumentException("Table cells must be strings, finite numbers, booleans, or null; use String.valueOf for other values");
                row.add(value);
            }
            rows.add(row);return this;
        }
        public Table rows(Object[][] values) { for(Object[] row:values)row(row);return this; }
        public Table rows(List<? extends List<?>> values) { for(List<?> row:values)row(row);return this; }
        public Table records(List<? extends Map<String, ?>> values) {
            if(columns.isEmpty()&&!values.isEmpty())columns(new ArrayList<>(values.get(0).keySet()));
            for(Map<String,?> value:values) {
                var row=new ArrayList<Object>();for(Column column:columns)row.add(value.get(column.id));row(row);
            }
            return this;
        }
        public Table search(boolean enabled) { options.put("search", enabled);return this; }
        public Table sortable(boolean enabled) { options.put("sort", enabled);return this; }
        public Table pagination(int pageSize) {
            if(pageSize<1||pageSize>1000)throw new IllegalArgumentException("Page size must be 1–1000");
            options.put("pagination",Map.of("limit",pageSize));return this;
        }
        public Table pagination(boolean enabled) { options.put("pagination",enabled?Map.of("limit",10):false);return this; }
        public Table width(int pixels) { Display.validateSize(pixels,520);width=pixels;return this; }
        public Table fixedHeader(int pixels) {
            if(pixels<100||pixels>2400)throw new IllegalArgumentException("Table height must be 100–2400 pixels");
            options.put("fixedHeader",true);options.put("height",pixels+"px");return this;
        }
        public Table resizable(boolean enabled) { options.put("resizable",enabled);return this; }
        public Table option(String key,Object value) {
            if(List.of("data","columns","server","from","title").contains(key))
                throw new IllegalArgumentException("Use the table's columns, row/rows/records, and title methods; only local data is supported");
            options.put(Objects.requireNonNull(key),value);return this;
        }
        public Map<String,Object> toOption() {
            if(columns.isEmpty())throw new IllegalArgumentException("A table needs at least one column");
            var ids=new HashSet<String>();
            for(Column column:columns)if(column==null||!ids.add(column.id))throw new IllegalArgumentException("Table column ids must be unique");
            for(List<Object> row:rows)if(row.size()!=columns.size())throw new IllegalArgumentException("Every row must match the number of columns");
            var result=new LinkedHashMap<>(options);
            result.put("title",title);result.put("columns",columns.stream().map(Column::toOption).toList());
            result.put("data",rows.stream().map(ArrayList::new).toList());return result;
        }
        public String toJson() { return JsonOptions.encode(toOption()); }
        public void show() {
            Display.emit("application/vnd.gridjs+json",title,width,520,toJson().getBytes(StandardCharsets.UTF_8));
        }
        @Override public String toString() { return "Grid.js table ("+columns.size()+" columns, "+rows.size()+" rows)"; }
    }

    public static final class Column {
        private final String name;
        private String id;
        private final Map<String,Object> options=new LinkedHashMap<>();
        private Column(String name) { this.name=Objects.requireNonNull(name);this.id=name; }
        public Column id(String id) { this.id=Objects.requireNonNull(id);return this; }
        public Column width(String width) { options.put("width",Objects.requireNonNull(width));return this; }
        public Column sortable(boolean enabled) { options.put("sort",enabled);return this; }
        public Map<String,Object> toOption() { var result=new LinkedHashMap<>(options);result.put("name",name);result.put("id",id);return result; }
    }
}
