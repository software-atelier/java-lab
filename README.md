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

On a system with a graphical desktop, Java Lab asks the OS to open the default browser once the server is ready. The URL is also written to the log. On a headless server, open **http://127.0.0.1:10081** manually (or use the configured port and context path). The automatic URL follows the actual listening port, including a dynamically assigned port.

**The default data directory is `{workingDir}/data`**, where `workingDir` is the directory you launch the application from, not the JAR's directory. It is created automatically. Notebooks, uploads and edited files live there. To choose another location:

```sh
LAB_DATA_DIR="$HOME/java-lab-files" java -jar /absolute/path/to/java-lab.jar
```

Relative `LAB_DATA_DIR` values start at the launch directory; absolute paths work too. The equivalent Spring option is `--lab.data-dir=/path/to/files`. The File Browser, CSV/AI file catalog, relative Java NIO `Path`/`Files` and CSV helper operations share this root, including for notebooks in subfolders. For legacy `java.io` APIs, pass `Path.of(...).toAbsolutePath().toFile()`; set an explicit working directory for native child processes. **Existing files are not moved automatically:** use `LAB_DATA_DIR=.` to continue using the previous launch-directory layout. Local notebook overrides are tied to the absolute notebook path; after manually relocating notebooks, supply their local values again if needed.

Change the port if needed:

```sh
java -jar target/java-lab.jar --server.port=8090
```

For development: `mvn spring-boot:run`. Tests: `mvn test`.

### Environment variables

Set variables in the process launching Java Lab, then restart it. Command-line Spring options take precedence over environment settings. The full reference is also available under **Help → Configuration & environment**; no configured secret values are displayed there.

| Variable | Default | Purpose |
| --- | --- | --- |
| `LAB_DATA_DIR` | `data` under the launch directory | Data root; created automatically. Relative values resolve against the launch directory. |
| `SERVER_PORT` | `10081` | HTTP listening port; equivalent to `--server.port`. |
| `SERVER_ADDRESS` | `127.0.0.1` | Listening address; default is local-only. |
| `SERVER_SERVLET_CONTEXT_PATH` | Empty | Optional URL prefix, e.g. `/lab`. |
| `AI_API_KEY` | Empty | Provider token; required for OpenRouter/authenticated providers. |
| `AI_MODEL` | Empty | Provider model ID; required to use Vibecode. |
| `AI_API_URL` | `https://openrouter.ai/api/v1` | OpenAI-compatible base URL or complete `/chat/completions` endpoint. |
| `AI_TIMEOUT` | `300s` | Overall model/tool/validation/correction timeout; durations such as `10m` are accepted. |
| `AI_MAX_TOKENS` | `128000` | Requested output ceiling per model response, subject to provider/model limits. |
| `AI_TOOLS_ENABLED` | `true` | Enable live Java session and library inspection. |
| `AI_MAX_TOOL_ROUNDS` | `16` | Maximum inspection rounds; range 1–64. |
| `AI_MAX_TOOL_CALLS` | `64` | Maximum attempted inspection calls; range 1–256. |
| `JAVA_HOME` | Launcher dependent | Used by Maven/Java launch scripts; ensure `java` on `PATH` is the intended full JDK 17+. |
| `PATH` | Operating system | Resolves Java, Maven and Linux's `xdg-open` browser launcher. |
| `JAVA_TOOL_OPTIONS` | Empty | Standard JVM/tool options, inherited by child Java processes. |
| `JDK_JAVA_OPTIONS` | Empty | Standard Java launcher options, inherited by child launchers. |
| `MAVEN_HOME` | Unset | Maven global settings location `conf/settings.xml`, after the `maven.home` Java property. |
| `M2_HOME` | Unset | Older Maven-home fallback, after `MAVEN_HOME`. |
| `MAVEN_OPTS` | Empty | Maven launcher JVM options for builds / `spring-boot:run`; not read directly by the executable JAR's embedded resolver. |
| `DISPLAY` | Desktop session | Linux X11 detection for automatically opening the browser. |
| `WAYLAND_DISPLAY` | Desktop session | Linux Wayland detection; headless servers use the URL in the log. |

Notebook environment bindings may refer to any additional variable names you choose, such as `JDBC_URL`, `JDBC_USER` or `API_TOKEN`; these have no global Java Lab default. Maven settings likewise support arbitrary `${env.NAME}` placeholders and environment activation conditions. Spring Boot's standard external configuration / relaxed environment bindings remain available beyond the common server controls listed above. JVM, Maven and desktop variables are runtime/launcher conventions rather than notebook settings.

## Workspace

- **Lab layout:** application menus, activity rail, file browser, launcher, notebook tabs, cell toolbar, and status bar.
- **Java notebooks:** native `.jnb` files containing executable code cells, Markdown notes, and raw cells. Existing `.ipynb` files remain accessible.
- **Syntax highlighting:** Java keywords, strings, text blocks, comments, numbers, types, annotations, and method calls are colored as you type.
- **Live Markdown preview:** double-click a Markdown cell for a split editor with an instant preview. Use **Done** or run the cell to return to the rendered view. Headings, emphasis, links, lists, blockquotes, tables, and fenced code are supported.
- **Java completion:** session-aware JShell suggestions for members, classes, imports, variables, and methods. Suggestions appear after a pause in typing — including a single letter after a dot — and stay open while you keep typing; **Ctrl + Space** requests them explicitly. Use arrow keys to select, **Enter** or **Tab** to insert, and **Escape** to dismiss.
- **Jupyter keyboard controls:** command/edit modes, range selection, cell clipboard, reversible cell operations, heading folding, editor commands, signature inspection, command palette, and notebook source search.
- **Bundled data libraries:** org.json **20260814** and Apache Commons CSV **1.14.1**, including its Commons IO and Commons Codec dependencies, available directly in notebook kernels and documented in Vibecode context.
- **Notebook variables and credentials:** define names, optional shareable defaults, and environment-variable bindings per notebook; keep personal values outside the `.jnb` file.
- **Notebook libraries:** add Maven coordinates to a notebook; Java Lab downloads their transitive runtime dependencies and makes them available only to that notebook's kernel.
- **Vibecode:** describe an AI edit for a whole Java cell or selected text using any OpenAI-compatible API; OpenRouter is the default. Optional preceding-cell context and additional notes help the model use your notebook definitions. Applied edits autosave and have a cell-level undo action.
- **Persistent sessions:** variables, methods, classes, and imports carry across cells. Each open notebook gets an independent JShell kernel in a separate JVM.
- **Outputs:** the result of the last statement is displayed automatically. Intermediate values stay quiet; charts and tables use their rich renderers. `log(...)`, console streams, compilation diagnostics, and runtime errors are also displayed.
- **Inline charts:** bundled JFreeChart 1.5.6 renders PNG charts, and Apache ECharts 6.0.0 renders interactive browser charts. Both persist with notebooks and are included in standalone HTML exports.
- **Interactive tables:** bundled Grid.js 6.2.0 provides searching, sorting, and pagination through a fluent Java wrapper, with persistence and offline HTML export.
- **Execution controls:** run selected cell, run and advance, run all (stops on error), interrupt, restart kernel, and **Restart & run all** to rebuild Java state after a refresh or timeout.
- **Editing:** insert, duplicate, reorder, delete, change cell type, and clear outputs. Double-click rendered Markdown to edit it.
- **Files:** all visible regular files appear in the File Browser with file-type icons. Notebooks and CSVs use their specialized tabs; JSON, XML, TXT and common source/text formats open in a highlighted text editor. Images open preview tabs with copyable embedding examples; other types download. Rename any file with F2, its File Browser/tab context menu, a tab-name double-click, or its editor title. Drag files onto another folder (or `..`) to move them; open editable files are saved first. Uploads open the matching editor/preview.
- **Create files:** the launcher's **START SOMETHING NEW** includes notebooks, CSV, JSON, XML, TXT, YAML and SQL files, plus image upload and the introduction notebook. Choose a filename in the current browser folder; names receive numeric suffixes on collision. JSON/XML/CSV receive valid starter contents; other text files start empty.
- **Beautiful HTML export:** **Export HTML** in the toolbar or **File → Export as HTML** downloads a standalone document with a styled cover, Markdown, syntax-highlighted Java, line numbers, and saved outputs. All styling is embedded; the exported file opens offline without the server. Responsive and print-friendly layouts are included.
- **Autosave:** saves 1.2 seconds after an edit, with explicit save available. Changes, notes, and outputs persist to disk using atomic replacement where supported.
- **Getting started:** the introduction launcher card creates and runs a sample notebook covering logging, Java state, formatted output, an interactive form, JSON/CSV, JFreeChart, Apache ECharts, and Grid.js. Click **A quick introduction** to create a fresh copy of the latest version.

### In-app help and usage guide

The **Help** menu provides a searchable offline guide with direct entries for **Getting started**, **Configuration & environment**, **Files & tabs**, **Text, JSON & XML files**, **Images & embedding**, **Cells & editing**, **Java & sessions**, **CSV files & helper**, **Variables & libraries**, **Charts & tables**, **Forms & formatted data**, **Vibecode & AI context**, **AI setup & diagnostics**, **Export & sharing**, and **Keyboard shortcuts & about**. Each topic includes practical steps, examples, relevant limits and recovery guidance. The guide also has a topic sidebar, full-text topic search and cross-links; it works without network access. The activity-bar Help button opens Getting started, while **H / ?** in notebook command mode or **Ctrl / Cmd + Shift + H** opens the full shortcut reference directly. Shortcut tables are generated from the current application bindings. **Escape**, the close button or **Done** closes help and returns focus to its opener.

### Jupyter-style keyboard controls

**Escape** enters command mode and **Enter** enters edit mode. The status bar shows the current mode. Command-mode letters act on cells; typing inside editors and form fields is preserved. The mappings follow JupyterLab 4.6 defaults with common classic Notebook aliases.

The complete implemented shortcut reference and upstream sources are in **[KEYBOARD_SHORTCUTS.md](KEYBOARD_SHORTCUTS.md)**. Open the same list from **Help → Keyboard shortcuts & about**, with **H** in command mode, or **Ctrl / Cmd + Shift + H**.

| Shortcut | Action |
| --- | --- |
| Shift + Enter | Run cell and select the next cell |
| Ctrl / Cmd + Enter | Run selected cell |
| Alt + Enter | Run cell and insert a new one below |
| Alt + Shift + V | Open Vibecode for the selected Java cell or selected editor text |
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
| Ctrl / Cmd + S | Save active notebook or CSV |
| Alt + N | New notebook |
| Escape | Dismiss popup or enter command mode |

Cell clipboard and undo/redo are browser-session state, and clipboard cells can be pasted between notebook tabs. Execution shortcuts apply to selected cells in document order. Line numbering, output visibility, scrolling, and layout flags are stored as notebook metadata. Search covers code, Markdown, and raw sources. The native text editor retains normal OS selection/navigation; next-occurrence selection uses a single selection rather than multiple cursors.

### Markdown links

Markdown cells support web, mail, workspace-file and heading links, including optional titles and angle-bracket destinations with spaces:

```markdown
[Website](https://example.com "Optional title")
[E-mail](mailto:help@example.com)
[Data](<Data/sales 2026.csv>)
[Image preview](opencode.png)
[Results](#results)

## Results
```

File paths are relative to the configured data root, not the notebook folder. Clicking a workspace link uses validated file metadata and opens its corresponding tab (or downloads an unsupported type). `./` and percent-encoded names are supported; hidden paths, parent traversal and unsafe URL protocols are rejected. Section anchors use normalized lower-case hyphenated headings, with `-1`, `-2` suffixes for duplicates; expand folded sections before jumping. Code spans/fences are not interpreted as links. Web links use a new tab with opener protection. Exported HTML keeps web/mail/section links and converts workspace file links to relative file links; distribute those files separately at the referenced paths. Markdown image assets, unlike ordinary file links, are embedded for offline viewing.

## Notebook settings

Open **Notebook settings** from the File menu to configure variables and libraries for the current notebook.

### Variables and credentials

Define a variable name and, optionally, a default value or the name of an environment variable. Read its effective value in a Java cell with `labVar("NAME")`; use `requireLabVar("NAME")` when a missing value should fail clearly. For example:

```java
String endpoint = requireLabVar("API_URL");
String token = requireLabVar("API_TOKEN");
```

The resolution order is **local value → environment variable → notebook default**. Definitions and non-secret defaults are saved in the `.jnb` file; local values are stored separately in `.java-lab-values` beside the configured data root (by default `{workingDir}/.java-lab-values`), with owner-only permissions where supported. Mark credentials as secret: they cannot have a notebook default, and their values are never returned by the settings API or included in a notebook download. You can also provide a value through the environment variable named in its definition before starting Java Lab.

**Notebook code is trusted Java code, not a sandbox.** A cell can print a credential or include it in an output that gets saved or exported. Clear sensitive outputs before sharing a notebook; do not put secrets in code cells or defaults.

### Maven libraries

Add dependencies using `groupId:artifactId:version`, for example `org.apache.commons:commons-lang3:3.17.0`. Java Lab downloads the library and its transitive compile/runtime JARs. It uses Maven Central by default and reads `~/.m2/settings.xml` (plus Maven's global `conf/settings.xml` when `MAVEN_HOME` or `M2_HOME` is set). Active profile repositories, mirrors, server credentials (including Maven-encrypted passwords in `settings-security.xml`), proxies, offline mode, and the configured local repository are honored. Environment and Java system-property placeholders in settings are resolved before parsing, including numeric proxy ports such as `${env.PROXY_PORT}`; active proxy ports must be between 1 and 65535. Repository credentials stay in Maven settings, not in the notebook. Profile activation supports `<activeProfiles>`, `activeByDefault`, and property/environment conditions; JDK, OS, and file activation are not supported. The dependency list is saved in the notebook; resolved JARs are added only to that notebook's JShell kernel. Applying a library change restarts that kernel, so rerun cells that define variables, methods, classes, or imports.

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

JSON and XML outputs also have a collapsible structured explorer. Use **Copy** to copy the source text or **Download** to save it as a file. The source remains available beneath the explorer.

Create interactive inputs from Java code with the pre-imported `Forms` helper:

```java
Forms.form("preferences")
    .title("Preferences")
    .text("name", "Name", "Ada").required("name")
    .select("role", "Role", "reader", "admin")
    .checkbox("enabled", "Enabled", true)
    .number("threshold", "Threshold", 10).range("threshold", 0, 100)
    .textarea("notes", "Notes", "")
    .email("email", "Email")
    .date("due", "Due date")
    .multiselect("topics", "Topics", "Java", "CSV", "Charts")
    .show();
```

In a later cell, read the current values:

```java
log("% (%): %", Forms.text("name"), Forms.text("role"), Forms.bool("enabled"));
double threshold = Forms.number("threshold");
Object raw = Forms.value("notes");
List<String> topics = Forms.list("topics");
```

The form supports text, textarea, number, single/multi-select, checkbox, email, and date fields. `.required(id)` marks a field mandatory, and `.range(id, min, max)` constrains a number field. Invalid input is shown at the field and rejected by the kernel before later code executes. After changing values, click **Apply values & run following cells** on the form. Saved dependent outputs are marked stale until rerun. Form and field IDs must start with a letter and contain letters, numbers, `_`, `.`, or `-` (maximum 100 characters). Field IDs identify values across the notebook, so use distinct IDs across forms. A rerun of the form cell preserves values already entered. Values are synchronized into the kernel before each later cell executes and are stored with the notebook; as with other Java state, restart/reopen requires rerunning code cells to recreate variables. Exported HTML displays saved form output but cannot execute later Java cells. Limits are 100 fields per form, 200 values per execution request, 100 KB of serialized values, and 16 total rich displays per snippet.

## JSON, XML and TXT editor tabs

Click a visible `.json`, `.xml` or `.txt` file to open a text-editor tab. Common text/source extensions (`.md`, `.yaml/.yml`, `.java`, `.sql`, `.properties`, `.log`, `.css`, `.js`, `.html/.htm`) use the same editor. Tabs retain independent drafts, cursor/scroll position and bounded undo history. **Save file**, **File → Save file** or **Ctrl / Cmd + S** persists edits; **Reload saved file** rereads disk after confirming before discarding a draft. Revision checks prevent stale writes over changes made by notebook code or another editor. Unsaved drafts remain after conflicts or format/validation errors.

The native textarea has synchronized, escaped syntax highlighting and line numbers. TXT stays plain text; highlighting becomes plain text above 300,000 characters for responsiveness. **Tab / Shift + Tab** indent/outdent, Enter auto-indents, brackets pair/wrap selections, **Ctrl / Cmd + F** finds text, and **Ctrl / Cmd + Z / Shift + Z** or **Ctrl + Y** undo/redo typing and assisted changes. **Format** / **Alt + Shift + F** pretty-prints JSON/XML; **Validate** checks syntax. JSON formatting preserves raw number literals (including large integers), duplicate keys and key order. XML formatting preserves mixed content and `xml:space`; DTD/entity declarations and very large/deep structures are outside formatter support. This is basic editing support, not a schema-aware language server. Invalid/incomplete content may still be saved deliberately as a draft.

Text files must be UTF-8 and at most **2 MB**. The editor preserves BOM and the detected line-ending style; mixed endings are normalized to that style on save. HTML/XML is never executed as markup. Supported images open previews; other binary/unsupported types download. Non-notebook file context menus include **Download file**, using attachment/octet-stream responses. Relative Java paths such as `Files.readString(Path.of("notes.txt"))` use the configured data root, matching the editor.

XML validation posts the **current draft** to the server's namespace-aware Java parser and returns well-formedness errors with line/column information. It does not require saving first or depend on browser-specific `parsererror` markup. XML formatting runs this validation before applying changes. DTDs, external entities and external schemas are disabled; this is not XSD validation. A result finishing after a tab switch stays with its original document.

## Image preview and embedding

PNG, JPEG/JPG, GIF, BMP, WebP and SVG files open a **read-only image tab**, with MIME type, dimensions, byte size, Reload and Download original. The launcher's **Image file** entry uploads an existing image. Under the preview are **Java** and **Markdown** examples using the exact data-root-relative path, each with a **Copy** button. Spaces/special characters are correctly quoted/encoded; if clipboard access is unavailable, select the displayed source directly. Moving or renaming an open image refreshes its examples and preview URL.

The pre-imported `Images` Java API displays a file as a rich, saved output:

```java
Images.show("images/photo.png");
// Optional display bounds, preserving aspect ratio:
Images.show(Path.of("images/photo.png"), 800, 600);
```

`show(String)` / `show(Path)` read when the cell runs. Outputs contain embedded image bytes and survive save/reopen/export even if the original file moves; rerun to refresh them. Supported formats match the preview. Image files are limited to 10 MB, 10,000 pixels per side / 40 million pixels, and share the per-snippet limit of 16 rich displays / 16 MB. SVG is parsed without DTDs/external entities and rendered in image mode, not inserted as active DOM markup; preview responses have a restrictive sandbox/content policy.

In a Markdown cell, use a path relative to the **configured data root**, even when the notebook is in a subfolder:

```markdown
![Description](<images/photo.png>)
```

The preview's copied example percent-encodes special characters. Markdown images may only target supported visible workspace files, not external/data/file URLs, hidden paths or `..` traversal. Missing files show an unavailable-image message. The notebook stores paths, so share image files along with the notebook. **Export HTML embeds referenced Markdown images** for offline use, as well as existing Java image outputs. Missing/invalid assets stop export rather than leaving server links. Markdown assets are bounded to 100 referenced files / 32 MB encoded data per export.

## Creating and renaming files

Use **START SOMETHING NEW** to create a notebook, CSV, JSON, XML, TXT, YAML or SQL file in the current File Browser folder. Choose a filename; extensions are appended when omitted, and collisions get a numeric suffix. YAML accepts `.yaml`/`.yml`. New CSVs contain `column1,column2`, JSON an empty object, XML a valid declaration/root, and other text files start empty. The image entry uploads an image rather than creating a blank bitmap.

The launcher intentionally offers document-creation cards for common working formats (CSV, JSON, XML, TXT, YAML, SQL) rather than every syntax-highlightable file extension. Use **File → New data/text file** to create another supported format, including Markdown, Java, JavaScript, CSS, HTML, Properties or Log.

All visible file types can be renamed from the **File Browser context menu**, **F2** on a file row, **File → Rename file**, a **tab-name double-click**, or the tab context menu. Clicking an editor/preview title also renames it (notebook titles retain inline editing). Open editable files save first; a failed save, busy operation or name collision prevents rename. Notebook kernels/local values are retained and notebook names keep the native `.jnb` extension. Other extension changes select the appropriate editor when possible. Renaming does not convert content or rewrite paths in Java/Markdown/other files. Update references manually or copy refreshed image examples.

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

### CSV files, table editor, and notebook helper

The File Browser lists `.csv` files (case-insensitive) alongside notebooks. Click a CSV to open its own **document tab** with an editable table. Multiple CSV files and notebooks share the tab bar; opening an already open file selects its existing tab. CSV tabs retain their independent unsaved edits, pagination, and scroll position when switching to another CSV, a notebook, or the launcher. CSV tabs do not create Java kernels, and notebook-only execution/AI commands are disabled while a CSV is selected.

Records are shown in pages of 50; values can contain commas, quotes, and embedded newlines. Add/delete records and columns, edit the first record as well as the data, and use **Save CSV**, **File → Save file**, or **Ctrl / Cmd + S** to persist changes. Close a CSV using its tab's close button or **File → Close tab**. Unsaved edits require confirmation before closing or reloading; switching tabs never discards or implicitly saves them. Dirty dots also mark unsaved background tabs; the status bar describes the active document. A revision check prevents saving over a file changed by notebook code or another editor; your edits remain available after a conflict. Saving/reloading a background CSV updates that document without replacing the active editor. Moving an open CSV first saves it and updates the same tab's path.

**Ctrl / Cmd + Shift + [ / ]** cycles through notebook, CSV and text tabs. **Alt + W / Ctrl + Shift + Q** closes the active document, and **Ctrl / Cmd + Shift + T** reopens the most recently closed document.

CSV files use UTF-8. Comma, semicolon, tab, and pipe are detected automatically; **Read delimiter** reloads the saved file using an explicit delimiter when detection is ambiguous. Saving preserves string values, including leading zeros and empty cells, the BOM, detected record-separator style, and whether a final newline was present. Quoting is normalized by Commons CSV rather than preserving the file's original byte-for-byte formatting. Ragged record widths are retained unless explicitly edited.

The pre-imported **`Csv`** helper uses the same format support:

```java
var salesCsv = Csv.load("Data/sales.csv");
var salesRecords = salesCsv.records();
Csv.save("Data/sales-copy.csv", salesCsv);
```

- `Csv.load(String)` / `load(Path)` auto-detect the delimiter; `load(String, char)` / `load(Path, Character)` select it explicitly.
- `table.rows()` returns every record, including the first. `headers()` returns the first record; `data()` returns the remaining records.
- `table.records()` returns `List<Map<String,String>>`, treating the first record as headers. It requires unique nonblank headers and records of matching width. All values remain strings; convert numbers explicitly.
- Tables and their rows/maps are immutable. For edits, copy the rows, then pass them to `withRows(...)`:

  ```java
  var editedRows = salesCsv.rows().stream().map(row -> new ArrayList<>(row)).toList();
  // Edit the copied cells as needed, for example editedRows.get(1).set(1, "42").
  var editedCsv = salesCsv.withRows(editedRows);
  Csv.save("Data/sales-edited.csv", editedCsv);
  ```

- `Csv.save(String, table)` / `save(Path, table)` create or replace a CSV using atomic replacement where supported. The parent folder must exist. Paths are relative to the **workspace root**, not the notebook's subfolder. Notebook code is trusted Java and may also use absolute paths.
- A `Csv.Table` cell result shows a compact dimensions summary, not all file contents. Its loaded rows remain available to later cells and session-inspection tools without printing them.

The helper/editor support **10 MB**, **10,000 records including the first**, **100 columns**, **100,000 cells**, and **100,000 characters per cell**. Use Commons CSV's streaming API directly for larger files. GUI reads/writes are restricted to visible workspace CSV files, with traversal and symlinks rejected.

**Vibecode knows saved workspace CSV files automatically**, even with preceding-cell context disabled. `WorkspaceCsvContext` supplies relative file paths, sizes, detected delimiters, and bounded first-record column names; it does not include subsequent data rows. The first record is assumed to be a header for this catalog, so headerless files need explicit handling using `rows()`. The catalog covers visible nested folders, skips hidden paths/symlinks, and is bounded to 50 files and about 24,000 characters, with truncation reported. Up to 20 column names of 80 characters are included per file. Directory traversal is bounded to 500 directories / 5,000 entries and the supported folder depth. Unsaved table edits are excluded; save first. `DataFormatsContext` documents the helper APIs for generated code.

## Charts with JFreeChart

**JFreeChart 1.5.6 is included in the executable JAR** and made available to every notebook kernel. No download or classpath setup is required when running the packaged app. The bundled library and display helper are unpacked to an application-owned temporary directory for JShell, and cleaned up on normal shutdown. Notebook files remain in the configured data directory.

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
| `AI_TOOLS_ENABLED` | Allow the model to inspect the current notebook session and library APIs | `true`; requires a model/provider supporting Chat Completions tools |
| `AI_TIMEOUT` | Overall generation timeout, including all model requests and tool rounds; accepts durations such as `300s` or `10m` | `300s` (5 minutes) |
| `AI_MAX_TOKENS` | Maximum output tokens per model response | `128000` |
| `AI_MAX_TOOL_ROUNDS` | Maximum model responses requesting inspection before final code generation; range 1–64 | `16` |
| `AI_MAX_TOOL_CALLS` | Maximum attempted inspection calls per generation; range 1–256 | `64` |

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

The default overall generation timeout is 300 seconds (5 minutes) and output budget per model response is 128,000 tokens. Optional Spring properties are `lab.ai.timeout`, `lab.ai.max-tokens`, `lab.ai.tools-enabled`, `lab.ai.max-tool-rounds`, `lab.ai.max-tool-calls`, and `lab.ai.api-url`; credentials and model use `lab.ai.api-key` and `lab.ai.model`.

For slower models or multi-round inspection, increase the timeout before starting the server:

```sh
export AI_TIMEOUT="10m"
java -jar target/java-lab.jar
```

You can also pass `--lab.ai.timeout=10m` when launching the JAR. Restart the server after changing configuration. This controls the whole generation; the separate five-second kernel inspection limit and ten-second connection-establishment limit are unchanged. Local generation timeouts, connection failures, and HTTP timeouts reported by the provider have distinct messages. A longer local timeout cannot override a provider/proxy timeout.

To reduce latency, choose a faster tool-capable model, disable preceding-cell context when it is unnecessary, and request focused edits. The model still has access to live session tools when preceding-cell context is off. `AI_MAX_TOKENS=16384` is an optional smaller output ceiling for typical cell edits; it limits excessively long generation but is not a guaranteed speed-up, and too small a ceiling can truncate responses (which are rejected). Further implementation optimizations could prefill session metadata to save an initial tool round or stream progress to improve feedback; the current provider uses non-streaming requests.

### Live session and library tools

When a notebook kernel is available and tools are enabled, Vibecode can inspect its **actual current state**, independently of the preceding-cell checkbox and saved outputs. Values do not need to have been printed. The model can use:

- **`session_overview`**: active variable names/types, imports, methods, classes, loaded JARs, and Maven coordinates/version metadata where present.
- **`inspect_variable`**: current scalar values and paged nested contents. Supports private and inherited object fields (where Java allows access), arrays, lists, other collections, and map entries/keys. Each child supplies a structured selector for deeper inspection. Cycles and inaccessible fields are marked. No model-supplied Java expressions, arbitrary getters, or object `toString()` methods are evaluated; collection interfaces are used to read collection contents.
- **`search_library_classes`**: case-insensitive class-name search across the kernel's actual dependency JARs, including transitive Maven dependencies.
- **`inspect_class`**: public constructors, methods/overloads (including inherited members), and fields for a fully qualified library/JDK class name or a simple session-defined type name, with filtering and pagination. This returns API signatures, not Javadoc prose, and does not initialize the class.

Tool results are sent to the configured AI provider. Internal notebook-variable storage and temporary JShell variables are excluded; known configured secret values are redacted, including in nested scalar previews. Other ordinary session data is available to the model. Set `AI_TOOLS_ENABLED=false` to use text-only models or disable live inspection.

Inspection runs on the kernel's serialized executor and is available while idle. It does not increment cell execution counts or create saved outputs. Each inspection has a five-second timeout. A generation defaults to up to 16 tool rounds/64 attempted calls, 50 entries per overview category, 25 children per variable page, 16 nested path steps, and 40 class/signature matches per page. Failed/malformed calls also consume the call budget. Scalar previews are limited to 1,000 characters, individual tool results to 24,000 characters, and accumulated tool result data to 120,000 characters. Oversized individual results are replaced with an explicit error asking for a narrower query.

When the round, call, or accumulated context limit is reached, the server retains the facts already collected and makes **one final model request without tools**, asking for replacement code. Excess calls in a batch receive explicit not-executed results; context results that exceed the budget are explicitly marked as omitted. The Live activity popup shows the transition to final code generation. If the model still requests tools after they have been disabled, generation fails with a specific error instead of continuing indefinitely. The prompt also asks the model to group independent lookups, avoid repeated inspections, and stop once it has enough information.

For particularly involved edits you can increase the inspection budget, for example:

```sh
AI_MAX_TOOL_ROUNDS=24 AI_MAX_TOOL_CALLS=96 AI_TIMEOUT=10m java -jar target/java-lab.jar
```

The configured AI timeout still covers the overall generation, **including the final code request**, with remaining time applied to each model request. Higher inspection limits do not reset that timeout. Cancel prevents applying a late response; server-side inspection/provider work may continue until it finishes or reaches its limits.

The English system prompt instructs the model to preserve useful results in meaningfully named **top-level JShell variables**, reuse existing session definitions, avoid repeated imports and duplicated logic, and omit output expressions that serve only to expose values. Generated code is still applied without executing it. A result based on a kernel that was replaced while generating is discarded.

### Output completeness and response diagnostics

Before returning code to the browser, the OpenAI-compatible provider checks the **resulting whole cell** with JShell completion analysis and Java syntax parsing. Selection replacements are inserted into the original source for this check, so valid partial expressions or blocks are not mistaken for complete cells. Parsing never evaluates snippets, changes the live kernel, runs annotation processors, or resolves notebook/library types. It detects unfinished blocks, loops, strings/text blocks, comments, and malformed syntax; it does not guarantee that APIs, SQL, types, or runtime behavior are correct.

If a provider reports `finish_reason: "length"`, or a supposedly finished response fails this check, its code is rejected and **one targeted correction request** is made. The correction receives the original task, collected facts, and the rejected answer (when present), asks for the full replacement rather than a continuation, and has no tools available. It uses the **remaining overall timeout**, without resetting inspection budgets or increasing the token limit. If that response is still truncated or malformed, generation fails and the original source and outputs remain unchanged. A correction may also follow the final no-tools request after inspection reaches its limit. Size limits, provider errors, and non-success finish reasons other than `length` remain explicit errors.

The Live activity popup also shows metadata for each model request:

- configured model ID and the model ID actually reported by the provider;
- the requested output token ceiling and the provider's `finish_reason`;
- reported input, output, and reasoning-token counts (when available).

The request configuration is visible while waiting; reported fields are filled in when a response arrives. Missing/invalid usage or model metadata is labelled **not reported**, rather than inferred or shown as zero. Reasoning tokens may already be included in the provider's output count; the display does not add them together. These diagnostics contain no prompt, generated source, raw provider errors, or variable values. A successful generation returns them in the `diagnostics` array; progress snapshots preserve them after failures as well.

On failure, **Last generation diagnostics** expands below the error. After a successful edit, reopen Vibecode on that cell to inspect its last diagnostics. This browser-session history is not saved in notebook files. Diagnostic metadata is retained independently of the short activity-event history, with at most 66 request records per server-side generation.

While generating, hover over **Generating code…** or focus it with the keyboard to open a **Live activity** popup. It shows the latest model request, session/library inspection activity, inspected variable or class names, elapsed time, and a short timestamped history. The popup remains open when moving the pointer onto it; its history can be scrolled. **Escape** dismisses the popup without cancelling generation. These are server-reported activity summaries, not the model's internal reasoning; prompts, generated code, variable values, and raw tool results are not included in the activity feed.

The browser adds a random `requestId` to its generation request and polls `GET /api/ai/progress/{requestId}` about every 750 ms. Progress responses are not cached. Polling stops on completion, failure, or cancellation; failed polls do not fail generation. After a generation error, a final progress fetch retrieves any last response diagnostics. The server keeps at most 32 activity events and 66 model-request diagnostic records per generation, and 128 tracked generations; it expires completed entries after five minutes and inactive entries after one hour, and can evict old completed entries when full. Requests without `requestId` retain generation, output checking, correction, and success-response diagnostics, but have no progress feed.

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

`start` and `end` are optional UTF-16 offsets selecting a nonempty range of `source`. If omitted, the entire cell is the target. The response contains `{ "code": "...", "model": "...", "diagnostics": [...] }`; for a selection, `code` is only the replacement section. Each diagnostic includes `request`, `configuredModel`, `reportedModel`, `maxTokens`, `finishReason`, `inputTokens`, `outputTokens`, and `reasoningTokens`; unreported provider fields are null.

The request may also include an optional `kernelId` identifying the notebook's current kernel. The browser supplies this automatically. With tools enabled, the server binds all inspection tools to that kernel; the model cannot select a different kernel. Requests without `kernelId` retain the context-only generation flow.

- **`AiContextProvider`** is a Spring extension point: additional beans can supply workspace files, kernel facts, documentation, retrieved references, or other context. `JavaRuntimeContext` supplies runtime details; `ChartContext`, `EChartsContext`, and `GridContext` supply the bundled visualization wrappers and their display APIs.
- **`ContextItem`** contains `kind`, `title`, `content`, and arbitrary JSON `metadata`; the browser already uses notebook, notes, and diagnostics entries.
- **`CodeGenerationService`** validates input and composes provider-neutral messages. It accepts up to 32 client context entries and a combined 200,000-character serialized context budget, including built-in context entries.
- **`CodeGenerationProvider`** isolates the model transport. `OpenAiCompatibleProvider` implements the standard OpenAI-compatible API transport; future providers can use the same edit and context contracts.

Instructions are limited to 10,000 characters and cell sources/replacements to 100,000 characters. Textual outputs are capped at 12,000 characters each and 64,000 characters in total, with recent outputs preferred and truncation reported in context metadata. Other oversized context is rejected with a visible error; model responses are never silently truncated before application. Provider errors are displayed inline, preserving the original cell.

## Notebook format

New notebooks use **`.jnb` (Java notebook)**. Their JSON structure retains the nbformat 4 cell and output model, with Java language metadata and a Java Lab format version, so the contents remain easy to inspect and process. Existing metadata is retained.

The file browser lists **all visible regular files** and supported folders in the configured data directory (`{workingDir}/data` by default). Hidden files and symbolic links are excluded. `.jnb` and legacy `.ipynb` files open as notebooks, `.csv` as editable tables, JSON/XML/TXT plus common source formats as text tabs, and supported images as previews; other types download. Create files/folders or upload data to organize the root. Empty folders can be deleted. Renaming a legacy notebook converts its extension to `.jnb`, and downloading it also uses `.jnb`. Files are not automatically renamed or relocated on startup. Internal path traversal and symlinks are rejected.

Use **Upload file** in the file browser to place a data file in the current folder. Uploads open their matching editor/image preview (the CSV API also returns a bounded preview). The upload limit is **10 MB**; upload filenames cannot contain separators or be notebook files (`.jnb`/`.ipynb`). Existing names receive a numeric suffix. Uploaded data remains separate from notebook files, except embedded Java outputs; HTML export additionally embeds referenced Markdown images.

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
  display/Formatted.java             Safe highlighted JSON/YAML/XML and code output
  display/Forms.java                 Kernel-local interactive form builder and values
  display/JsonOptions.java           Shared JSON serialization for display wrappers
  display/Display.java               Shared typed chart output transport
  KernelController.java              Kernel REST API
  ai/                                Generation contracts, context providers, OpenAI-compatible transport/API
src/main/resources/static/
  index.html                         Lab workspace
  help.js / help.css                 Searchable offline usage guide, topic navigation, and live shortcut reference
  styles.css                         Responsive styling
  app.js                             Notebook editor and API client
  editor.css                         Highlighting, preview, completion styling
  editor.js                          Completion popup and keyboard interaction
  presentation.js                    Shared rendering and standalone HTML export
  getting-started.js                 Runnable introduction notebook
  output-explorer.css                Structured JSON/XML output explorer
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
| GET | `/api/notebooks?folder=Projects` | Configured data directory, all visible file entries (notebook/csv/text/image/file), and child folders; omit `folder` for the data root |
| POST | `/api/notebooks?folder=Projects` | Create; optional `{ "name": "Example.jnb" }` |
| GET / PUT / DELETE | `/api/notebooks/{name}?folder=Projects` | Read / save notebook JSON / delete |
| PATCH | `/api/notebooks/{name}?folder=Projects` | Rename with `{ "name": "New.jnb" }` |
| POST | `/api/notebooks/move` | Move a notebook with `{ "path": "Projects/Analysis.jnb", "folder": "Archive" }`; workspace data files can also be moved via API. |
| GET / PUT | `/api/notebooks/{name}/values?folder=Projects` | Inspect value sources / set or clear local values without returning their contents |
| POST | `/api/notebooks/folders` | Create `{ "path": "Projects/2026" }` |
| PATCH / DELETE | `/api/notebooks/folders?path=Projects/2026` | Rename an empty folder with `{ "name": "New name" }` / delete an empty folder |
| POST | `/api/notebooks/uploads?folder=Projects` | Multipart `file`; returns stored name/path/size and CSV preview rows |
| GET | `/api/csv?path=Projects/data.csv` | Read a CSV table plus revision; optional `delimiter` selects comma, semicolon, tab or pipe |
| PUT | `/api/csv?path=Projects/data.csv` | Save `{ "table": { "rows": [...], "delimiter": ",", "recordSeparator": "\n", "bom": false, "trailingNewline": true }, "revision": "..." }`; rejects conflicting external edits |
| GET | `/api/files/text?path=Projects/notes.txt` | Read supported UTF-8 text (normalized LF), syntax format, BOM/line-ending metadata, revision and byte size |
| PUT | `/api/files/text?path=Projects/notes.txt` | Save `{ "content": "...", "bom": false, "recordSeparator": "\n", "revision": "..." }`; atomic replacement / conflict checks |
| GET | `/api/files/download?path=Projects/image.png` | Download any visible regular file as an attachment |
| POST | `/api/files` | Create `{ "folder": "Projects", "name": "data.json", "extension": "json" }`; returns name/path/type/format without overwriting |
| PATCH | `/api/files/rename` | Rename `{ "path": "Projects/old.txt", "name": "new.txt" }`; retains folder, rejects conflicts |
| POST | `/api/files/validate-xml` | Validate current draft `{ "content": "<root/>" }`; returns valid/message/line/column without saving |
| GET | `/api/files/image-info?path=Projects/photo.png` | Image MIME/dimensions/size/revision |
| GET | `/api/files/image?path=Projects/photo.png` | Validated image bytes for preview/Markdown, no-cache and sandbox headers |
| GET | `/api/files/image-data?path=Projects/photo.png` | Image metadata plus embedded data URI for standalone export |
| POST | `/api/kernels` | Create an independent Java kernel; pass `{ "notebook": "Projects/Example.jnb" }` for its variables and Maven dependencies |
| POST | `/api/kernels/{id}/execute` | Execute `{ "code": "1 + 1", "formValues": { "name": "Ada" } }` |
| POST | `/api/kernels/{id}/complete` | Analyze `{ "code": "System.out.pr", "cursor": 13 }`; returns replacement anchor and suggestions |
| POST | `/api/kernels/{id}/inspect` | Return Java signatures at the cursor without executing code |
| POST | `/api/kernels/{id}/interrupt` | Interrupt current execution |
| DELETE | `/api/kernels/{id}` | Shut down kernel |
| GET | `/api/ai/config` | Provider/model and configuration availability (no credentials) |
| POST | `/api/ai/generate` | Generate a whole-cell or selection replacement with extensible context |
| GET | `/api/ai/progress/{requestId}` | Latest generation activity and timestamped history; optional ID supplied with the generation request |

The optional frontend rendering tests require Node.js 18+ and no npm dependencies:

```sh
node --test src/test/js/*.test.cjs
```
