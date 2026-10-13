package ch.software_atelier.java_lab.display;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class GridBuilderTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void buildsTablesFromJavaRowsWithTypedCellsAndOptions() throws Exception {
        var table=Grid.table().title("Sales \"report\"").columns("Name","Amount","Active")
            .row("Ada <script>",42,true).row(List.of("Grace",12.5,false)).row("Unknown",null,true)
            .search(true).sortable(true).pagination(2).fixedHeader(300).width(900)
            .option("language",Map.of("search",Map.of("placeholder","Suchen…")));
        var option=json.readTree(table.toJson());
        assertThat(option.path("title").asText()).isEqualTo("Sales \"report\"");
        assertThat(option.path("columns")).hasSize(3);assertThat(option.path("data")).hasSize(3);
        assertThat(option.path("data").get(0).get(0).asText()).isEqualTo("Ada <script>");
        assertThat(option.path("data").get(0).get(1).isNumber()).isTrue();
        assertThat(option.path("data").get(0).get(2).isBoolean()).isTrue();
        assertThat(option.path("data").get(2).get(1).isNull()).isTrue();
        assertThat(option.path("pagination").path("limit").asInt()).isEqualTo(2);
        assertThat(option.path("height").asText()).isEqualTo("300px");
        assertThat(option.path("language").path("search").path("placeholder").asText()).isEqualTo("Suchen…");
    }
    @Test void mapsRecordsToExplicitColumnIdsAndSupportsArrays() throws Exception {
        var table=Grid.table().columns(Grid.column("Person").id("name"),Grid.column("Sales").id("sales").sortable(false).width("100px"))
            .records(List.of(Map.of("sales",19,"name","Ada"),Map.of("sales",12,"name","Grace")));
        var option=json.readTree(table.toJson());
        assertThat(option.path("data").get(0).get(0).asText()).isEqualTo("Ada");
        assertThat(option.path("data").get(1).get(1).asInt()).isEqualTo(12);
        assertThat(option.path("columns").get(1).path("sort").asBoolean()).isFalse();
        assertThat(json.readTree(Grid.table().columns("A","B").rows(new Object[][]{{"x",1},{"y",2}}).toJson()).path("data")).hasSize(2);
        assertThat(json.readTree(Grid.fromRecords(List.of(Map.of("name","Ada"))).toJson()).path("data").get(0).get(0).asText()).isEqualTo("Ada");
    }
    @Test void rejectsInvalidShapesUnsupportedCellsNonFiniteNumbersAndRemoteSources() {
        assertThatThrownBy(()->Grid.table().toJson()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Grid.table().columns("A","B").row("x").toJson()).hasMessageContaining("match");
        assertThatThrownBy(()->Grid.table().columns("A","A").toJson()).hasMessageContaining("unique");
        assertThatThrownBy(()->Grid.table().columns("A").row(new Object())).hasMessageContaining("cells");
        assertThatThrownBy(()->Grid.table().columns("A").row(Double.NaN).toJson()).hasMessageContaining("finite");
        assertThatThrownBy(()->Grid.table().option("server",Map.of("url","https://example.com"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Grid.table().pagination(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
