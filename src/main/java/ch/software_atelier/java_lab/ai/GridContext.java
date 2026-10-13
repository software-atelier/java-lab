package ch.software_atelier.java_lab.ai;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class GridContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("library","Grid.js interactive Java tables","""
            Grid.js 6.2.0 JS and CSS are bundled offline. ch.software_atelier.java_lab.display.Grid is pre-imported in Java cells.
            Use the fluent Java wrapper; no JSON text or browser JavaScript is required.
            Grid.table() returns Grid.Table. Builder methods: title(String), columns(String...),
            columns(List<String>), columns(Grid.Column...), row(Object...), row(List<?>), rows(Object[][]),
            rows(List<? extends List<?>>), records(List<? extends Map<String,?>>), search(boolean),
            sortable(boolean), pagination(int pageSize), pagination(boolean), width(int pixels),
            fixedHeader(int heightPixels), resizable(boolean), option(String,Object).
            Finish with show() (void), or Grid.show(table). A standalone Grid.Table expression also displays it.
            Grid.column(String name) returns a column builder supporting id(String), width(String CSS width),
            sortable(boolean). Use ids to map record keys to custom display names.
            Grid.fromRecords(records) infers columns from the first map; supply explicit column ids for controlled order.
            Every row must match the number of columns. Cells are strings, finite numbers, booleans, or null.
            Convert dates or other objects to text explicitly via String.valueOf(...). Arbitrary objects are not echoed.
            Search, sorting, and pagination are enabled by default (10 rows per page). Numeric cells sort numerically.
            Data is local; server-backed fetch, raw HTML cells, JavaScript formatters/callbacks, and CDN assets are not supported.
            Advanced option() values use Java Maps/Lists/arrays (e.g. language settings). Data and columns use the wrapper methods.
            toOption() returns a Java Map and toJson() is available for debugging.
            Tables persist as application/vnd.gridjs+json outputs and stay interactive in offline HTML exports.
            Limits: 10,000 rows, 1,000,000 serialized characters, page size 1–1000, width/height 100–2400 pixels.
            Tables share the per-snippet 16-display / 16 MB output limit with charts.
            Only the last statement result is displayed automatically; supported charts/tables render richly and other values as text.
            Use log("Rows: %", count) for additional text output (Object... arguments are accepted).

            Complete runnable Java table example:
            Grid.table()
                .title("Monthly sales")
                .columns("Month", "Sales")
                .row("Jan", 12)
                .row("Feb", 19)
                .row("Mar", 15)
                .search(true)
                .sortable(true)
                .pagination(2)
                .show();
            """,Map.of("library","Grid.js","version","6.2.0","displayHelper","ch.software_atelier.java_lab.display.Grid")));
    }
}
