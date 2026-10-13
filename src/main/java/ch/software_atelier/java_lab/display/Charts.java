package ch.software_atelier.java_lab.display;

import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Runs inside each notebook's remote JShell JVM, not in the web server. */
public final class Charts {
    private Charts() {}

    public static void show(JFreeChart chart) { show(chart, 900, 520); }

    public static synchronized void show(JFreeChart chart, int width, int height) {
        Objects.requireNonNull(chart, "Chart is required");
        Display.validateSize(width, height);
        try {
            var png = new ByteArrayOutputStream();
            ChartUtils.writeChartAsPNG(png, chart, width, height);
            byte[] image = png.toByteArray();
            String title = chart.getTitle() == null ? "Java chart" : chart.getTitle().getText();
            Display.emit("image/png", title, width, height, image);
        } catch (IOException e) { throw new IllegalStateException("Could not render chart as PNG", e); }
    }

}
