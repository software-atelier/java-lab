package ch.software_atelier.java_lab.display;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class EChartsBuilderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void buildsMixedChartsFromJavaDataAndEscapesStrings() throws Exception {
        String title="A \"quoted\" chart\nwith tabs\tand backslash \\";
        var chart=ECharts.chart().title(title).categories(List.of("Jan", "Feb", "Mar")).yAxis("Revenue")
            .bar("Sales",List.of(12,19,15)).line("Actual",new double[]{10.5,18.0,14.2})
            .series(ECharts.lineSeries("Target",new double[]{15,17,19}).smooth().color("#dd713c").labels())
            .zoom().size(900,450).option("toolbox",Map.of("feature",Map.of("saveAsImage",Map.of())));
        var option=json.readTree(chart.toJson());
        assertThat(option.path("title").path("text").asText()).isEqualTo(title);
        assertThat(option.path("xAxis").path("type").asText()).isEqualTo("category");
        assertThat(option.path("xAxis").path("data").get(1).asText()).isEqualTo("Feb");
        assertThat(option.path("series")).hasSize(3);
        assertThat(option.path("series").get(1).path("data").get(0).asDouble()).isEqualTo(10.5);
        assertThat(option.path("series").get(2).path("smooth").asBoolean()).isTrue();
        assertThat(option.path("series").get(2).path("itemStyle").path("color").asText()).isEqualTo("#dd713c");
        assertThat(option.path("dataZoom")).hasSize(2);
        assertThat(option.path("toolbox").path("feature").has("saveAsImage")).isTrue();
    }

    @Test void supportsPieScatterAndOrdinaryJavaOptionValues() throws Exception {
        var pie=json.readTree(ECharts.chart().title("Share").pie("Share",Map.of("Java",65,"Other",35)).toJson());
        assertThat(pie.has("xAxis")).isFalse();
        assertThat(pie.path("tooltip").path("trigger").asText()).isEqualTo("item");
        assertThat(pie.path("series").get(0).path("data")).hasSize(2);
        var scatter=json.readTree(ECharts.chart().scatter("Points",new double[][]{{1,2},{3,4}}).xAxisValue("X").yAxis("Y").toJson());
        assertThat(scatter.path("xAxis").path("type").asText()).isEqualTo("value");
        assertThat(scatter.path("series").get(0).path("data").get(1).get(1).asInt()).isEqualTo(4);
        var option=json.readTree(ECharts.chart().option("series",List.of(ECharts.barSeries("Bars",1,2,3))).toJson());
        assertThat(option.path("series").get(0).path("type").asText()).isEqualTo("bar");
    }

    @Test void rejectsNonFiniteNumbersCyclesUnsupportedValuesAndInvalidSizes() {
        assertThatThrownBy(()->ECharts.chart().bar("Bad",Double.NaN).toJson()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
        assertThatThrownBy(()->ECharts.chart().line("Bad",Double.POSITIVE_INFINITY).toJson()).isInstanceOf(IllegalArgumentException.class);
        var cyclic=new LinkedHashMap<String,Object>();cyclic.put("self",cyclic);
        assertThatThrownBy(()->ECharts.chart().option("custom",cyclic).toJson()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cyclic");
        assertThatThrownBy(()->ECharts.chart().option("custom",new Object()).toJson()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
        assertThatThrownBy(()->ECharts.chart().size(0,500)).isInstanceOf(IllegalArgumentException.class);
    }
}
