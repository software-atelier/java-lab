package ch.software_atelier.java_lab.ai;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Always included in generation requests, even when notebook context is disabled. */
@Component
public class ChartContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("library", "JFreeChart notebook charts", """
            JFreeChart 1.5.6 is bundled and on the notebook JShell classpath. No installation or dependency setup is needed.
            Preloaded imports: org.jfree.chart.*, org.jfree.data.category.*, org.jfree.data.xy.*,
            org.jfree.data.general.*, org.jfree.data.time.*, and ch.software_atelier.java_lab.display.Charts.
            To display a chart inline, call Charts.show(org.jfree.chart.JFreeChart chart) (900 x 520 PNG),
            or Charts.show(chart, int width, int height). These methods return void.
            Images appear in notebook outputs, persist in .jnb files, and are embedded in HTML exports.
            A standalone JFreeChart expression (e.g. chart as the last snippet) also renders inline.
            Do not use Swing, ChartFrame, ChartPanel, GUI windows, or filesystem image output to show charts.
            The kernel is headless. Use standard JFreeChart datasets/factories; XY and time-series APIs are available.
            Sizes must be 100–2400 pixels per side, at most 4 million pixels. Up to 16 images / 16 MB per snippet.
            For org.jfree.chart.plot.PlotOrientation, add an explicit import or use the fully qualified name.
            Explicit Charts.show is recommended for clarity, multiple charts, and custom dimensions.

            Bar chart example (complete runnable cell):
            var sales = new DefaultCategoryDataset();
            sales.addValue(12, "Sales", "Jan");
            sales.addValue(19, "Sales", "Feb");
            sales.addValue(15, "Sales", "Mar");
            var bar = ChartFactory.createBarChart("Monthly sales", "Month", "Sales", sales);
            Charts.show(bar);

            XY line chart example (complete runnable cell):
            var series = new XYSeries("Growth");
            series.add(1, 2); series.add(2, 5); series.add(3, 9);
            var points = new XYSeriesCollection(series);
            var line = ChartFactory.createXYLineChart("Growth", "Time", "Value", points);
            Charts.show(line, 1000, 600);

            Pie chart example (complete runnable cell):
            var shares = new DefaultPieDataset<String>();
            shares.setValue("Java", 65); shares.setValue("Other", 35);
            Charts.show(ChartFactory.createPieChart("Language share", shares, true, true, false));
            """, Map.of("library", "org.jfree:jfreechart", "version", "1.5.6", "displayHelper", "ch.software_atelier.java_lab.display.Charts")));
    }
}
