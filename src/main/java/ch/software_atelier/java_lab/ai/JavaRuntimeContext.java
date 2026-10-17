package ch.software_atelier.java_lab.ai;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class JavaRuntimeContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("runtime", "Java Lab execution environment",
            "Java " + Runtime.version().feature() + " using JShell. Cells share notebook session state. "
                + "Relative Java file paths resolve against the configured data directory (LAB_DATA_DIR; default launch workingDir/data), not the notebook's subfolder. "
                + "Prefer Path/Files and the Csv helper; pass absolute Path.toAbsolutePath() values to legacy java.io APIs or explicit working directories to native processes. "
                + "Preloaded imports: java.util.*, java.util.stream.*, java.time.*, java.nio.file.*. "
                + "JFreeChart 1.5.6 and Apache ECharts 6.0.0 displays are bundled. Charts and ECharts helpers are pre-imported. "
                + "Grid.js 6.2.0 tables are bundled and the Grid Java wrapper is pre-imported. "
                + "org.json 20260814 and Apache Commons CSV 1.14.1 are bundled; org.json.*, org.apache.commons.csv.*, and BOMInputStream are pre-imported. "
                + "Csv (ch.software_atelier.java_lab.data.Csv) is pre-imported for loading/saving workspace CSV tables. "
                + "Images is pre-imported: Images.show(\"images/photo.png\") displays a workspace-relative PNG/JPEG/GIF/BMP/WebP/SVG image, without a wrapping application. Images.show(path,width,height) controls display size. Existing image outputs are embedded snapshots. "
                + "The result of the last statement in a cell is displayed automatically: charts/tables use their rich renderer, other values use text. "
                + "Intermediate results are not echoed. Explicit show() and console/log outputs are displayed wherever called. "
                + "Use the pre-defined void log(String msg, Object... args) function for additional text output. Each literal '%' is replaced by the next argument, "
                + "left to right. Numbers, booleans, arrays and objects can be passed directly; arrays use readable deep formatting. Unmatched placeholders remain '%'; extra arguments "
                + "are ignored. Replacement text is literal and is not formatted or processed again. Example: log(\"Hello %, total = %\", name, total); "
                + "log prints one line to the cell's console output. System.out and System.err output and compilation/runtime errors are also displayed. "
                + "Notebook settings may declare variables and Maven dependencies. Access a configured variable with labVar(\"NAME\"), which returns null when unset, "
                + "or requireLabVar(\"NAME\"), which fails clearly when unset. Use these calls for credentials; never print, log, or hard-code their values. "
                + "A notebook-settings context entry lists only variable names, secret flags, environment-variable bindings, and Maven coordinates; "
                + "it never includes resolved or local values. Maven dependencies and their transitive libraries are available only in the declaring notebook's kernel. "
                + "Other third-party Java dependencies are not installed unless declared for this notebook.", Map.of()));
    }
}
