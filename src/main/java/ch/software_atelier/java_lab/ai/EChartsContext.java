package ch.software_atelier.java_lab.ai;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class EChartsContext implements AiContextProvider {
    @Override public List<CodeGeneration.ContextItem> contextFor(CodeGeneration.Request request) {
        return List.of(new CodeGeneration.ContextItem("library", "Apache ECharts interactive notebook charts", """
            Apache ECharts 6.0.0 JavaScript runtime is bundled for offline browser rendering.
            Java cells have ch.software_atelier.java_lab.display.ECharts pre-imported, with a fluent Java wrapper.
            PREFER THE JAVA WRAPPER over hand-written JSON. ECharts.chart() creates an ECharts.Chart.
            Chart methods return the builder: title(String), subtitle(String), categories(String...),
            categories(List<String>), xAxis(String name), yAxis(String name), xAxisValue(String name),
            bar(String name, Number... values), line(String name, Number... values),
            bar/line(String name, double[] values), bar/line(String name, List<? extends Number> values),
            scatter(String name, double[][] points), pie(String name, Map<String,? extends Number> values),
            series(ECharts.Series), legend(boolean), tooltip(boolean), tooltipAxis(), tooltipItem(), zoom(),
            size(int width, int height), and option(String key, Object javaValue).
            Finish a chain with show() or show(int width, int height), which return void.
            ECharts.show(ECharts.Chart) and ECharts.show(ECharts.Chart,int width,int height) also work.
            Static series factories: ECharts.barSeries(name, Number...), ECharts.lineSeries(name, Number...),
            barSeries/lineSeries also accept double[] or List<? extends Number>,
            ECharts.scatterSeries(name, double[][]), ECharts.pieSeries(name, Map<String,? extends Number>).
            Series supports smooth(), smooth(boolean), stack(String), color(String), labels(), area(),
            and option(String,Object) for styling. Use series(factory(...).smooth()) to style a line.
            Advanced option() values can be ordinary Java Maps, Lists, arrays, strings, finite numbers,
            booleans, or null. The wrapper serializes and escapes them automatically. toJson() and toOption()
            inspect the generated options. A standalone Chart expression also displays it inline.
            The legacy ECharts.show(String JSON) overload is available for compatibility, but normal
            generated examples should use the Java wrapper. No script tags or browser APIs are needed.
            Charts are interactive in the notebook, persist as application/vnd.apache.echarts+json outputs,
            and remain interactive in standalone HTML exports (the runtime is embedded).
            Tooltip rendering uses canvas rich text. JavaScript callback functions are not supported.
            Pass Java-computed numbers, lists, and arrays directly to the wrapper.
            Options are limited to 1,000,000 serialized characters; dimensions
            are 100–2400 pixels per side, at most 4 million pixels. Chart limits are shared with JFreeChart.
            JFreeChart remains available through Charts.show(JFreeChart) when a static PNG is preferred.

            Complete runnable wrapper cell:
            ECharts.chart()
                .title("Monthly revenue")
                .categories("Jan", "Feb", "Mar")
                .yAxis("Revenue")
                .bar("Revenue", 12, 19, 15)
                .series(ECharts.lineSeries("Target", 15, 17, 19).smooth())
                .size(900, 450)
                .show();
            """, Map.of("library", "Apache ECharts", "version", "6.0.0", "displayHelper", "ch.software_atelier.java_lab.display.ECharts")));
    }
}
