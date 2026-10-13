# Java Lab

[![Build JAR](https://github.com/software-atelier/java-lab/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/software-atelier/java-lab/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/software-atelier/java-lab?label=release)](https://github.com/software-atelier/java-lab/releases/latest)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](#build-and-run)
[![Download JAR](https://img.shields.io/badge/download-JAR-blue)](https://github.com/software-atelier/java-lab/releases/latest/download/java-lab.jar)

<p align="center">
  <img src="assets/java-lab-mascot.png" alt="Java Lab mascot: a fox with a coffee mug and a code notebook" width="320">
</p>

A JupyterLab-style notebook workspace for Java, built on **Spring Boot** and **JShell**. One executable JAR contains the server, embedded web server, browser interface, and application dependencies. No frontend build, CDN, database, Python installation, or add-ons are needed.

## Download

Download the [latest executable JAR](https://github.com/software-atelier/java-lab/releases/latest/download/java-lab.jar) and run it with a full JDK 17+:

```sh
java -jar java-lab.jar
```

Every commit on `main` also produces a JAR in the [GitHub Actions build artifacts](https://github.com/software-atelier/java-lab/actions/workflows/build.yml). Tagged releases provide the direct download above.

## Build and run

Requirements: a **full JDK 17+** (including `jdk.jshell`) and **Maven 3.6.3+** to build. Maven is not needed to run the packaged app.

```sh
mvn clean package
java -jar target/java-lab.jar
```

Open **http://127.0.0.1:8080**.

**Notebooks are stored in the directory you launch the application from**, rather than next to the JAR. To use a different workspace, launch the JAR from that directory with its absolute path:

```sh
java -jar /absolute/path/to/java-lab.jar
```

Change the port if needed:

```sh
java -jar target/java-lab.jar --server.port=8090
```

For development: `mvn spring-boot:run`. Tests: `mvn test`.

## Workspace

- **Lab layout:** application menus, activity rail, file browser, launcher, notebook tabs, cell toolbar, and status bar.
- **Java notebooks:** native `.jnb` files containing executable code cells, Markdown notes, and raw cells. Existing `.ipynb` files remain accessible.
- **Syntax highlighting:** Java keywords, strings, text blocks, comments, numbers, types, annotations, and method calls are colored as you type.
- **Live Markdown preview:** double-click a Markdown cell for a split editor with an instant preview. Use **Done** or run the cell to return to the rendered view. Headings, emphasis, links, lists, blockquotes, tables, and fenced code are supported.
- **Java completion:** session-aware JShell suggestions for members, classes, imports, variables, and methods. Suggestions appear after a pause in typing; **Ctrl + Space** requests them explicitly. Use arrow keys to select, **Enter** or **Tab** to insert, and **Escape** to dismiss.
- **Jupyter keyboard controls:** command/edit modes, range selection, cell clipboard, reversible cell operations, heading folding, editor commands, signature inspection, command palette, and notebook source search.
- **Bundled data libraries:** org.json **20260814** and Apache Commons CSV **1.14.1**, including its Commons IO and Commons Codec dependencies, available directly in notebook kernels and documented in Vibecode context.
- **Notebook variables and credentials:** define names, optional shareable defaults, and environment-variable bindings per notebook; keep personal values outside the `.jnb` file.
- **Notebook libraries:** add Maven coordinates to a notebook; Java Lab downloads their transitive runtime dependencies and makes them available only to that notebook's kernel.
- **Vibecode:** describe an AI edit for a whole Java cell or selected text using any OpenAI-compatible API; OpenRouter is the default. Optional preceding-cell context and additional notes help the model use your notebook definitions. Applied edits autosave and have a cell-level undo action.
- **Persistent sessions:** variables, methods, classes, and imports carry across cells. Each open notebook gets an independent JShell kernel in a separate JVM.
- **Outputs:** the result of the last statement is displayed automatically. Intermediate values stay quiet; charts and tables use their rich renderers. `log(...)`, console streams, compilation diagnostics, and runtime errors are also displayed.
- **Inline charts:** bundled JFreeChart 1.5.6 renders PNG charts, and Apache ECharts 6.0.0 renders interactive browser charts. Both persist with notebooks and are included in standalone HTML exports.
- **Interactive tables:** bundled Grid.js 6.2.0 provides searching, sorting, and pagination through a fluent Java wrapper, with persistence and offline HTML export.
- **Execution controls:** run selected cell, run and advance, run all (stops on error), interrupt, and restart kernel.
- **Editing:** insert, duplicate, reorder, delete, change cell type, and clear outputs. Double-click rendered Markdown to edit it.
- **Files:** create, filter, open, rename, delete, and download notebooks. Right-click a file for rename/delete actions.
- **Beautiful HTML export:** **Export HTML** in the toolbar or **File → Export as HTML** downloads a standalone document with a styled cover, Markdown, syntax-highlighted Java, line numbers, and saved outputs. All styling is embedded; the exported file opens offline without the server. Responsive and print-friendly layouts are included.
- **Autosave:** saves 1.2 seconds after an edit, with explicit save available. Changes, notes, and outputs persist to disk using atomic replacement where supported.
- **Getting started:** the introduction launcher card creates and runs a 13-cell sample notebook with logging, last-result display, JFreeChart, Apache ECharts, and Grid.js examples. Click **A quick introduction** to create a fresh copy of the latest version.

### Jupyter-style keyboard controls

**Escape** enters command mode and **Enter** enters edit mode. The status bar shows the current mode. Command-mode letters act on cells; typing inside editors and form fields is preserved. The mappings follow JupyterLab 4.6 defaults with common classic Notebook aliases.

The complete implemented shortcut reference and upstream sources are in **[KEYBOARD_SHORTCUTS.md](KEYBOARD_SHORTCUTS.md)**. Open the same list from **Help**, with **H** in command mode, or **Ctrl / Cmd + Shift + H**.

| Shortcut | Action |
| --- | --- |
| Shift + Enter | Run cell and select the next cell |
| Ctrl / Cmd + Enter | Run selected cell |
| Alt + Enter | Run cell and insert a new one below |
| Escape / Enter | Command mode / edit mode |
| J / K or ↓ / ↑ | Move between cells in command mode |
| Shift + J / K or Shift + ↓ / ↑ | Extend cell selection |
| A / B | Insert cell above / below |
| Y / M / R | Code / Markdown / raw cell |
| X / C / V | Cut / copy / paste selected cells |
| D, D | Delete selected cells |
| Z / Shift + Z | Undo / redo cell action |
| Shift + M | Merge cells |
| Ctrl + Shift + - | Split cell at cursor |
| I, I / 0, 0 | Interrupt / restart kernel |
| Ctrl / Cmd + Shift + C | Command palette |
| Ctrl / Cmd + F / H | Find / find and replace notebook source |
| Tab / Shift + Tab | Completion or indent / signature inspection or outdent |
| Ctrl + Space | Request Java completion |
| ↑ / ↓ | Navigate completion suggestions |
| Enter / Tab | Accept the selected suggestion |
| Ctrl / Cmd + S | Save notebook |
| Alt + N | New notebook |
| Escape | Dismiss popup or enter command mode |

Cell clipboard and undo/redo are browser-session state, and clipboard cells can be pasted between notebook tabs. Execution shortcuts apply to selected cells in document order. Line numbering, output visibility, scrolling, and layout flags are stored as notebook metadata. Search covers code, Markdown, and raw sources. The native text editor retains normal OS selection/navigation; next-occurrence selection uses a single selection rather than multiple cursors.

## Notebook settings

Open **Notebook settings** from the File menu to configure variables and libraries for the current notebook.

### Variables and credentials

Define a variable name and, optionally, a default value or the name of an environment variable. Read its effective value in a Java cell with `labVar("NAME")`; use `requireLabVar("NAME")` when a missing value should fail clearly. For example:

```java
String endpoint = requireLabVar("API_URL");
String token = requireLabVar("API_TOKEN");
```

The resolution order is **local value → environment variable → notebook default**. Definitions and non-secret defaults are saved in the `.jnb` file; local values are stored separately on this computer in a `.java-lab-values` directory beside the notebook working directory, with owner-only permissions where supported. Mark credentials as secret: they cannot have a notebook default, and their values are never returned by the settings API or included in a notebook download. You can also provide a value through the environment variable named in its definition before starting Java Lab.

**Notebook code is trusted Java code, not a sandbox.** A cell can print a credential or include it in an output that gets saved or exported. Clear sensitive outputs before sharing a notebook; do not put secrets in code cells or defaults.

### Maven libraries

Add dependencies using `groupId:artifactId:version`, for example `org.apache.commons:commons-lang3:3.17.0`. Java Lab downloads the library and its transitive compile/runtime JARs. It uses Maven Central by default and reads `~/.m2/settings.xml` (plus Maven's global `conf/settings.xml` when `MAVEN_HOME` or `M2_HOME` is set). Active profile repositories, mirrors, server credentials (including Maven-encrypted passwords in `settings-security.xml`), proxies, offline mode, and the configured local repository are honored. Repository credentials stay in Maven settings, not in the notebook. Profile activation supports `<activeProfiles>`, `activeByDefault`, and property/environment conditions; JDK, OS, and file activation are not supported. The dependency list is saved in the notebook; resolved JARs are added only to that notebook's JShell kernel. Applying a library change restarts that kernel, so rerun cells that define variables, methods, classes, or imports.

## Java execution

Write Java snippets directly; a `main` method is unnecessary:

```java
List<Integer> numbers = List.of(2, 4, 6, 8);
int total = numbers.stream().mapToInt(Integer::intValue).sum();
log("Total = %", total);
total
```

The next cell can use `total`. Multiple snippets within a cell execute in order. A failed snippet stops the rest of that cell. Imports for `java.util.*`, `java.util.stream.*`, `java.time.*`, and `java.nio.file.*` are preloaded. Additional standard-library imports can be written in cells.

### Text output with `log`

Every notebook session has a predefined function:

```java
void log(String msg, Object... args)
```

It provides parameterized logging with **`%` placeholders**:

```java
log("Hello %, total = %", "Ada", 42);
// Hello Ada, total = 42

log("Progress: %", "80%");
// Progress: 80%

log("Ready");
// Ready

log("Values: %", new int[] {1, 2, 3});
// Values: [1, 2, 3]
```

Each literal `%` is replaced by the next object argument, from left to right. Numbers and booleans can be passed directly, ordinary objects use their string representation, and arrays use readable deep formatting. Arguments are inserted literally and are not processed again. Unmatched placeholders remain `%`, extra arguments are ignored, and null values print as `null`. This is placeholder substitution, not `printf` formatting: use `%`, rather than `%s` or `%d`. Each call writes one line to the cell's console output, which is saved and included in HTML exports. No import or manual string conversion is needed.

The **result of the last statement** creates an automatic output, similar to a Jupyter notebook. Earlier statement results are not echoed. JFreeChart, `ECharts.Chart`, and `Grid.Table` values use their dedicated renderers; other values use their Java/JShell text representation. Trailing comments do not suppress the final result. Void statements produce no result, and returned `PrintStream` objects are suppressed because their console text is already shown. Explicit `.show()` calls, `log`, `System.out` / `System.err` writes, and errors appear wherever they occur in the cell. Unsupported rich MIME types remain hidden; stored text results are displayed and exported.

Completion analyzes code at the caret using the current notebook's Java session, without executing it. For example, run a cell defining `String greeting = "Hello";`, then type `greeting.toU` in another cell and request completion. Definitions and imports must have been executed to enter the session; completion is not a project-wide IDE language server. It is available when the kernel is idle, and the replacement range accounts for earlier snippets in the same cell.

Notebook contents and outputs survive application restarts; **live Java session state does not**. Reopen and run cells to reconstruct it. Browser refresh also starts a new session. Closing a tab shuts down its kernel. Idle kernels expire after one hour; use **Restart kernel** to reconnect. Execution is limited to 30 seconds per cell, source to 100,000 characters per cell, captured stdout/stderr to 1 MB per stream between snippets, and active kernels to 32. Outputs support text, embedded chart PNGs, interactive ECharts charts, and Grid.js tables. Console input and arbitrary rich HTML outputs are not implemented.

The app binds to `127.0.0.1` by default. Java code executes with your user's local filesystem and process permissions, as in a local REPL.

## Formatted output and forms

Use the pre-imported `Formatted` helper to display code or data with syntax highlighting. The content is treated as text, never executed as HTML or JavaScript. Supported formats are `json`, `yaml` (`yml`), `xml`, `html`, `css`, `javascript` (`js`), `java`, `sql`, `markdown` (`md`), `csv`, and `text`.

```java
Formatted.prettyJson("{\"name\":\"Ada\",\"active\":true}"); // validates and indents JSON
Formatted.prettyYaml("person: {name: Ada, active: true}");
Formatted.prettyXml("<person><name>Ada</name></person>");
Formatted.show("sql", "select * from people where active = true;");
```

`Formatted.json`, `.yaml`, `.xml`, and `.code(format, content)` are convenient aliases. `show` preserves whitespace. `prettyJson` validates and indents JSON objects/arrays; `prettyYaml` parses YAML safely and emits indented block-style YAML; `prettyXml` indents XML using a parser that rejects DTDs and external entities. Invalid or unsafe input produces a cell error. YAML reformatting may normalize scalar quoting and discard comments. Each call creates a saved notebook output and appears in offline HTML exports.

Create interactive inputs from Java code with the pre-imported `Forms` helper:

```java
Forms.form("preferences")
    .title("Preferences")
    .text("name", "Name", "Ada")
    .select("role", "Role", "reader", "admin")
    .checkbox("enabled", "Enabled", true)
    .number("threshold", "Threshold", 10)
    .textarea("notes", "Notes", "")
    .show();
```

In a later cell, read the current values:

```java
log("% (%): %", Forms.text("name"), Forms.text("role"), Forms.bool("enabled"));
double threshold = Forms.number("threshold");
Object raw = Forms.value("notes");
```

Form and field IDs must start with a letter and contain letters, numbers, `_`, `.`, or `-` (maximum 100 characters). Field IDs identify values across the notebook, so use distinct IDs across forms. A rerun of the form cell preserves values already entered. Values are synchronized into the kernel before each later cell executes and are stored with the notebook; as with other Java state, restart/reopen requires rerunning code cells to recreate variables. Exported HTML displays saved form output but cannot execute later Java cells. Limits are 100 fields per form, 200 values per execution request, 100 KB of serialized values, and 16 total rich displays per snippet.

## JSON and CSV

The executable JAR bundles **`org.json:json:20260814`** and **Apache Commons CSV 1.14.1**, plus Commons IO **2.20.0** and Commons Codec **1.19.0**. The org.json version is the latest published release verified against [Maven Central metadata](https://repo.maven.apache.org/maven2/org/json/json/maven-metadata.xml) on **2026-10-12**. Versions are pinned for reproducible builds.

These imports are ready in every Java notebook kernel:

```java
import org.json.*;
import org.apache.commons.csv.*;
import org.apache.commons.io.input.BOMInputStream;
```

### JSON

```java
var data = new JSONObject()
    .put("name", "Ada")
    .put("sales", 42)
    .put("items", new JSONArray(List.of(1, 2, 3)));

log("Sales for %: %", data.getString("name"), data.getInt("sales"));
log("JSON:\n%", data.toString(2));
```

Use `new JSONObject(jsonText)` / `new JSONArray(jsonText)` to parse JSON. `toMap()` / `toList()` bridge to Java values and Grid data. `JSONObject.NULL` represents JSON null; passing Java null to `put` removes the key.

### Robust CSV parsing

Commons CSV handles quoted delimiters, doubled quotes, embedded newlines, CRLF, empty fields, named headers, and configurable CSV/TSV dialects. CSV records return strings; explicitly convert numeric fields when building charts or sortable tables.

```java
String csv = "name,sales\n\"Doe, Ada\",42\nGrace,19\n";
var format = CSVFormat.RFC4180.builder()
    .setHeader()
    .setSkipHeaderRecord(true)
    .get();

try (var parser = CSVParser.parse(csv, format)) {
    var table = Grid.table().title("CSV data").columns("Name", "Sales");
    for (CSVRecord row : parser) {
        table.row(row.get("name"), Integer.parseInt(row.get("sales")));
    }
    table.show();
}
```

For UTF-8 files that may have a byte-order mark:

```java
var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();
try (var input = BOMInputStream.builder().setPath(Path.of("data.csv")).get();
     var reader = new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8);
     var parser = CSVParser.parse(reader, format)) {
    for (CSVRecord row : parser) log("%", row.toMap());
}
```

Use `CSVFormat.TDF` for tab-separated data, `setDelimiter(';')` for semicolon CSV, and `CSVPrinter` for correctly quoted output. Close parsers/readers with try-with-resources. Malformed input reports a parsing error instead of silently splitting fields. **`DataFormatsContext`** supplies these library versions, APIs, examples, and BOM guidance to Vibecode automatically.

## Charts with JFreeChart

**JFreeChart 1.5.6 is included in the executable JAR** and made available to every notebook kernel. No download or classpath setup is required when running the packaged app. The bundled library and display helper are unpacked to an application-owned temporary directory for JShell, and cleaned up on normal shutdown. Notebook files remain in the working directory.

Create a chart and show it inline:

```java
var sales = new DefaultCategoryDataset();
sales.addValue(12, "Sales", "Jan");
sales.addValue(19, "Sales", "Feb");
sales.addValue(15, "Sales", "Mar");
var chart = ChartFactory.createBarChart("Monthly sales", "Month", "Sales", sales);
Charts.show(chart);
```

Or create an XY line chart:

```java
var series = new XYSeries("Growth");
series.add(1, 2);
series.add(2, 5);
series.add(3, 9);
var dataset = new XYSeriesCollection(series);
var line = ChartFactory.createXYLineChart("Growth", "Time", "Value", dataset);
Charts.show(line, 1000, 600);
```

`Charts.show(JFreeChart chart)` renders a **900 × 520** PNG. The overload `Charts.show(chart, width, height)` accepts custom dimensions. Both return `void`, so no helper object is echoed as a cell result. You can display multiple charts in one cell, or reuse a chart defined in a previous cell. Evaluating a standalone `JFreeChart` expression, such as `chart`, also displays it automatically.

The following imports are preloaded:

```java
import org.jfree.chart.*;
import org.jfree.data.category.*;
import org.jfree.data.xy.*;
import org.jfree.data.general.*;
import org.jfree.data.time.*;
import ch.software_atelier.java_lab.display.Charts;
```

Other JFreeChart classes can be imported normally. For example, `PlotOrientation` lives in `org.jfree.chart.plot`. The kernel runs headlessly; display charts with `Charts.show`, rather than Swing windows or `ChartFrame`.

Charts are stored as standard `display_data` outputs containing base64 `image/png`, a text title, and dimensions. They survive save/reopen and are embedded as data images in **Export HTML**, including offline viewing and printing. Allowed dimensions are 100–2400 pixels per side, with at most 4 million pixels; each Java snippet can emit up to 16 charts / 16 MB of PNG data.

Vibecode always receives the bundled JFreeChart version, preloaded imports, `Charts.show` signatures, display behavior, and complete bar/line/pie examples through **`ChartContext`**. Try a prompt such as “Create a line chart of revenue over the last six months and display it in the notebook.” This library context is supplied even if preceding-cell context is disabled.

## Interactive charts with Apache ECharts

**Apache ECharts 6.0.0 is bundled in the JAR**, including its full browser runtime. The pre-imported `ECharts` class provides a fluent Java wrapper; data can be Java numbers, arrays, or lists:

```java
ECharts.chart()
    .title("Monthly revenue")
    .categories("Jan", "Feb", "Mar")
    .yAxis("Revenue")
    .bar("Revenue", 12, 19, 15)
    .series(ECharts.lineSeries("Target", 15, 17, 19).smooth())
    .size(900, 450)
    .show();
```

The default size is **900 × 520**. `.size(width, height)` sets a maximum display width and fixed height; chart width adapts to the available notebook space. `.show()` and `.show(width, height)` return `void`. You can keep a builder in a variable across cells, call `ECharts.show(chart)`, or evaluate a standalone chart variable to display it.

### Java wrapper API

- **Chart setup:** `.title(text)`, `.subtitle(text)`, `.categories(String...)` / `.categories(List<String>)`, `.xAxis(name)`, `.yAxis(name)`, `.xAxisValue(name)`.
- **Series:** `.bar(name, Number...)`, `.line(name, Number...)`, and overloads accepting `double[]` or `List<? extends Number>`. `.scatter(name, double[][])` accepts XY coordinates; `.pie(name, Map<String, ? extends Number>)` accepts named values.
- **Styled series:** `.series(ECharts.lineSeries(...).smooth())`. Static factories also include `barSeries`, `scatterSeries`, and `pieSeries`. Series methods include `.stack(name)`, `.color(color)`, `.labels()`, `.area()`, and `.smooth(boolean)`.
- **Interaction:** `.legend(boolean)`, `.tooltip(boolean)`, `.tooltipAxis()`, `.tooltipItem()`, and `.zoom()`.
- **Advanced options:** `.option(key, value)` on a chart or series accepts ordinary Java Maps, Lists, arrays, strings, finite numbers, booleans, and null. For example, `.option("toolbox", Map.of("feature", Map.of("saveAsImage", Map.of())))`. The wrapper handles JSON escaping and serialization automatically.
- **Inspection:** `.toOption()` produces a Java Map; `.toJson()` exposes the serialized options for debugging.

Examples using Java-computed data:

```java
List<Integer> revenue = List.of(12, 19, 15);
ECharts.chart().title("Revenue").categories("Jan", "Feb", "Mar")
    .bar("Revenue", revenue).show();

ECharts.chart().title("Language share")
    .pie("Share", Map.of("Java", 65, "Other", 35)).show();

ECharts.chart().title("Measurements").xAxisValue("Time").yAxis("Value")
    .scatter("Sample", new double[][] {{1, 2}, {2, 5}, {3, 9}}).show();
```

Interactive legends and hover tooltips work in the notebook and exported documents. Tooltips render as canvas rich text. Formatter templates can be strings; JavaScript callback functions are not supported. The original `ECharts.show(String optionJson)` overload is retained for existing notebooks, while new examples and Vibecode prompts use the Java wrapper.

The option object and dimensions are saved in `display_data` under `application/vnd.apache.echarts+json`. Reopening a notebook reconstructs the chart. **Export HTML embeds the ECharts runtime and chart options**, so the export remains interactive offline without the app or CDN access. PNG charts remain embedded images alongside ECharts charts.

Options are limited to 1,000,000 characters. The size, count, and total-output limits are shared with JFreeChart: 100–2400 pixels per side, at most 4 million pixels, and at most 16 charts / 16 MB per Java snippet.

Vibecode receives the ECharts version, fluent Java chart/series methods, supported Java data types, and a runnable wrapper example through **`EChartsContext`**, in addition to JFreeChart context. The Getting Started notebook demonstrates a JFreeChart bar chart and a Java-built ECharts bar/line comparison with a legend and hover tooltips.

## Tables with Grid.js

**Grid.js 6.2.0 JavaScript and CSS are bundled in the executable JAR**. The `Grid` Java wrapper is pre-imported in notebook kernels:

```java
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
```

Search, sorting, and pagination are enabled by default (10 rows per page). Numeric cells sort numerically. Tables use plain-text strings, finite numbers, booleans, and null; HTML-looking strings are displayed as text. Convert dates or other objects explicitly with `String.valueOf(...)`.

### Java table API

- **Schema:** `.columns(String...)`, `.columns(List<String>)`, or `.columns(Grid.Column...)`. All rows must match the number of columns, and column ids must be unique.
- **Rows:** `.row(Object...)`, `.row(List<?>)`, `.rows(Object[][])`, or `.rows(List<? extends List<?>>)`.
- **Records:** `Grid.fromRecords(List<? extends Map<String,?>>)` infers columns from the first record. For controlled order or custom display names, use `.columns(Grid.column("Name").id("name"), ...).records(records)`.
- **Columns:** `Grid.column(name).id(key).width("120px").sortable(false)`.
- **Interaction/layout:** `.search(boolean)`, `.sortable(boolean)`, `.pagination(int)`, `.pagination(boolean)`, `.width(int)`, `.fixedHeader(int)`, and `.resizable(boolean)`.
- **Advanced local options:** `.option(key, javaValue)` accepts Java Maps/Lists/arrays, for example `.option("language", Map.of("search", Map.of("placeholder", "Suchen…")))`. Use the dedicated methods for title, columns, and data. Server-backed data and JavaScript callbacks are not supported.
- **Display:** `.show()` or `Grid.show(table)`. A final `Grid.Table` expression also renders automatically. `.toOption()` and `.toJson()` inspect generated options.

Tables are stored as `application/vnd.gridjs+json` outputs and reconstruct when the notebook is reopened. **Export HTML embeds Grid.js and its stylesheet**, so search, sorting, and pagination continue to work offline alongside both chart libraries.

Limits: 10,000 rows, 1,000,000 serialized characters, 1–1000 rows per page, and 100–2400 pixels maximum width / fixed-header height. Tables share the per-snippet limit of 16 displays / 16 MB with charts.

Vibecode always receives **`GridContext`** with the version, wrapper methods, data types, limitations, and a runnable Java example, even when preceding-cell context is disabled. The Getting Started notebook includes a searchable, sortable sales table with three rows per page.

## Vibecode with OpenAI-compatible APIs

**All OpenAI-compatible APIs are supported.** Java Lab uses the standard Chat Completions request/response format. OpenRouter remains the default, and `AI_API_URL` selects another hosted or local provider.

### Environment configuration

| Variable | Purpose | Default |
| --- | --- | --- |
| `AI_API_KEY` | Provider API key, sent as a Bearer token when set | Empty; required by OpenRouter and authenticated providers |
| `AI_MODEL` | Model ID accepted by the selected provider | Empty; must be set |
| `AI_API_URL` | API base URL including its version/path prefix | `https://openrouter.ai/api/v1` |

Set these environment variables **in the process that launches Java Lab**:

```sh
export AI_API_KEY="your-openrouter-api-key"
export AI_MODEL="openai/gpt-4.1"
java -jar target/java-lab.jar
```

With `AI_API_URL` unset or empty, these settings use OpenRouter. To use OpenAI directly:

```sh
export AI_API_URL="https://api.openai.com/v1"
export AI_API_KEY="your-openai-api-key"
export AI_MODEL="gpt-4.1"
java -jar target/java-lab.jar
```

For a local OpenAI-compatible endpoint, for example Ollama:

```sh
export AI_API_URL="http://localhost:11434/v1"
export AI_MODEL="qwen2.5-coder:7b"
unset AI_API_KEY
java -jar target/java-lab.jar
```

The app appends `/chat/completions` to the base URL, preserving prefixes such as `/v1` or `/api/v1`. A complete URL ending in `/chat/completions` is also accepted, and trailing slashes are normalized. URL query parameters are preserved. Custom APIs without authentication can leave `AI_API_KEY` unset; no empty Authorization header is sent.

Use a model ID accepted by your provider. Restart the server after changing the environment. Credentials are read by the server and are never sent to the browser or stored in notebook files. Java Lab also runs without AI configuration; the Vibecode dialog explains missing settings.

**Migration:** rename `OPENROUTER_API_KEY` to `AI_API_KEY` and `OPENROUTER_MODEL` to `AI_MODEL`. The former `lab.ai.openrouter.*` Spring properties are now `lab.ai.*`, with `base-url` renamed to `api-url`.

1. Select a Java cell, optionally selecting the exact code section to replace.
2. Click **✦ Vibecode** on the toolbar or the cell's action bar.
3. Describe the change. **Include preceding cells and outputs** is enabled by default; deselect it for a cell-only request. Textual results from earlier runs can help Vibecode use discovered table schemas and other data. Expand **Additional context** for requirements, sample data, or API notes.
4. Click **Generate & apply**. Generated code replaces the cell or selected range, clears that cell's stale outputs, and autosaves. Run it using the normal execution controls.
5. **Undo AI edit** on the cell restores the prior source and outputs. Undo is session-local and avoids overwriting subsequent manual code changes.

If a Java cell fails, click **✦ Fix with AI** below its error output. Vibecode sends the complete cell and its error diagnostics, immediately requests a correction, and applies the returned code without running it. The same **Undo AI edit** action restores the original code and error output. The AI provider must be configured; the dialog shows a configuration hint if it is not.

Requests send the target source, instruction, chosen context, current-cell errors when present, and Java runtime details to the configured AI API. With preceding cells enabled, their saved textual outputs are also sent; binary images and large chart payloads are excluded. Outputs may contain sensitive data, so clear them or turn off this option before generating when needed. Generating code does not execute it. Cancel dismisses the request and prevents a late response from changing the notebook; the upstream provider may still finish processing it.

Vibecode also receives the current notebook's configured variable names, secret flags, environment-variable bindings, and Maven coordinates, even when preceding-cell context is disabled. It knows to use `labVar("NAME")` or `requireLabVar("NAME")`. Notebook defaults, locally stored values, and resolved environment values are not added to this settings context. As with any AI request, code or notes you explicitly include in the target or optional context are sent to the configured provider.

The default request timeout is 120 seconds and output budget is 128,000 tokens. Optional Spring properties are `lab.ai.timeout`, `lab.ai.max-tokens`, and `lab.ai.api-url`; credentials and model use `lab.ai.api-key` and `lab.ai.model`.

### Extensible AI context

The API separates the instruction, edit target, and context:

```json
{
  "prompt": "Make this a reusable method",
  "target": {"source": "System.out.println(42);", "start": null, "end": null},
  "context": [
    {"kind": "notes", "title": "Requirements", "content": "Use standard Java only", "metadata": {}}
  ]
}
```

`start` and `end` are optional UTF-16 offsets selecting a nonempty range of `source`. If omitted, the entire cell is the target. The response is `{ "code": "...", "model": "..." }`; for a selection, `code` is only the replacement section.

- **`AiContextProvider`** is a Spring extension point: additional beans can supply workspace files, kernel facts, documentation, retrieved references, or other context. `JavaRuntimeContext` supplies runtime details; `ChartContext`, `EChartsContext`, and `GridContext` supply the bundled visualization wrappers and their display APIs.
- **`ContextItem`** contains `kind`, `title`, `content`, and arbitrary JSON `metadata`; the browser already uses notebook, notes, and diagnostics entries.
- **`CodeGenerationService`** validates input and composes provider-neutral messages. It accepts up to 32 client context entries and a combined 200,000-character serialized context budget, including built-in context entries.
- **`CodeGenerationProvider`** isolates the model transport. `OpenAiCompatibleProvider` implements the standard OpenAI-compatible API transport; future providers can use the same edit and context contracts.

Instructions are limited to 10,000 characters and cell sources/replacements to 100,000 characters. Textual outputs are capped at 12,000 characters each and 64,000 characters in total, with recent outputs preferred and truncation reported in context metadata. Other oversized context is rejected with a visible error; model responses are never silently truncated before application. Provider errors are displayed inline, preserving the original cell.

## Notebook format

New notebooks use **`.jnb` (Java notebook)**. Their JSON structure retains the nbformat 4 cell and output model, with Java language metadata and a Java Lab format version, so the contents remain easy to inspect and process. Existing metadata is retained.

The file browser lists `.jnb` and legacy `.ipynb` files directly in the working directory; nested folders are not currently browsed. Existing `.ipynb` files can still be opened and saved under their original names. Renaming a legacy notebook converts its extension to `.jnb`; downloading it also uses `.jnb`. Files are not automatically renamed on startup. Symbolic-link notebook files and directory traversal are rejected.

HTML export is a read-only presentation of the current saved notebook, including its stored outputs; exporting does not run any cells. Raw HTML in Markdown and code/output text is escaped. Live Java sessions remain in Java Lab.

Java Lab executes Java only. Opening a Python notebook does not translate its code. The included kernelspec describes the language; it does not install a Java kernel into a separate Jupyter installation.

## Structure

```text
pom.xml                              Executable JAR build
src/main/java/ch/software_atelier/java_lab/
  JavaLabApplication.java            Spring Boot entry point
  NotebookStore.java                 Working-directory storage
  NotebookController.java            Notebook REST API
  KernelService.java                 JShell sessions and execution
  KernelClasspath.java               Bundled libraries for remote JShell
  display/Charts.java                Headless chart rendering in each kernel
  display/Logs.java                  Parameterized logging behind the notebook log function
  display/ECharts.java               Fluent Java chart/series wrapper and display helper
  display/Grid.java                  Fluent Java Grid.js table/column wrapper
  display/JsonOptions.java           Shared JSON serialization for display wrappers
  display/Display.java               Shared typed chart output transport
  KernelController.java              Kernel REST API
  ai/                                Generation contracts, context providers, OpenAI-compatible transport/API
src/main/resources/static/
  index.html                         Lab workspace
  styles.css                         Responsive styling
  app.js                             Notebook editor and API client
  editor.css                         Highlighting, preview, completion styling
  editor.js                          Completion popup and keyboard interaction
  presentation.js                    Shared rendering and standalone HTML export
  charts.js                          ECharts rendering, resizing, disposal, and export assets
  tables.js / tables.css             Grid.js rendering, disposal, styling, and export assets
  notebook-model.js                  Cell selection, clipboard data, structural undo/redo
  notebook-controls.js / .css        Jupyter-style modes, keys, palette, search, and editor commands
  vibecode.js                        AI prompt, context, selection edits, and undo
src/test/java/ch/software_atelier/java_lab/           Storage and execution tests
src/test/js/                         Rendering and export tests (Node built-ins)
```

### API

| Method | Endpoint | Description |
| --- | --- | --- |
| GET | `/api/notebooks` | Working directory and notebook listing |
| POST | `/api/notebooks` | Create; optional `{ "name": "Example.jnb" }` |
| GET / PUT / DELETE | `/api/notebooks/{name}` | Read / save notebook JSON / delete |
| PATCH | `/api/notebooks/{name}` | Rename with `{ "name": "New.jnb" }` |
| GET / PUT | `/api/notebooks/{name}/values` | Inspect value sources / set or clear local values without returning their contents |
| POST | `/api/kernels` | Create an independent Java kernel; pass `{ "notebook": "Example.jnb" }` for its variables and Maven dependencies |
| POST | `/api/kernels/{id}/execute` | Execute `{ "code": "1 + 1" }` |
| POST | `/api/kernels/{id}/complete` | Analyze `{ "code": "System.out.pr", "cursor": 13 }`; returns replacement anchor and suggestions |
| POST | `/api/kernels/{id}/inspect` | Return Java signatures at the cursor without executing code |
| POST | `/api/kernels/{id}/interrupt` | Interrupt current execution |
| DELETE | `/api/kernels/{id}` | Shut down kernel |
| GET | `/api/ai/config` | Provider/model and configuration availability (no credentials) |
| POST | `/api/ai/generate` | Generate a whole-cell or selection replacement with extensible context |

The optional frontend rendering tests require Node.js 18+ and no npm dependencies:

```sh
node --test src/test/js/*.test.cjs
```
