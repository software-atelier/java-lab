package ch.software_atelier.java_lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@Timeout(60)
class KernelSessionInspectionTest {
    private final KernelService kernels = new KernelService();
    private final ObjectMapper mapper = new ObjectMapper();
    @AfterEach void close() { kernels.close(); }

    private JsonNode inspect(String id, String tool, String args) throws Exception {
        return mapper.valueToTree(kernels.inspectSession(id, tool, mapper.readTree(args)));
    }

    @Test void inspectsCurrentPrivateNestedValuesWithoutPrintingOrCallingGettersOrToString() throws Exception {
        String id = kernels.create();
        var setup = kernels.execute(id, """
            class Address { private String city = "Bern"; }
            class BaseCustomer { private String inheritedName = "Ada"; }
            class Customer extends BaseCustomer {
                private Address address = new Address();
                private Customer self = this;
                public String getCity() { throw new AssertionError("getter must not run"); }
                public String toString() { throw new AssertionError("toString must not run"); }
            }
            var customers = new ArrayList<Customer>();
            customers.add(new Customer());
            var grouped = Map.of("active", customers);
            int[] counts = {1, 2, 3};
            var tags = new LinkedHashSet<String>(List.of("one", "two"));
            int nextCount(int n) { return n + 1; }
            """
        );
        assertThat(setup.success()).as(setup.outputs().toString()).isTrue();
        var overview = inspect(id, "session_overview", "{}");
        assertThat(overview.path("variables").toString()).contains("customers", "grouped", "counts").doesNotContain("__lab", "\"$");
        assertThat(overview.path("methods").toString()).contains("nextCount");
        assertThat(overview.path("imports").toString()).contains("java.util.*");
        assertThat(overview.path("types").toString()).contains("Customer", "Address");
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"customers\"}").path("total").asInt()).isEqualTo(1);
        var nested = inspect(id, "inspect_variable", """
            {"variable":"customers", "path":[{"index":0},{"field":"address"},{"field":"city"}]}
            """);
        assertThat(nested.path("value").asText()).isEqualTo("Bern");
        var customer = inspect(id, "inspect_variable", "{\"variable\":\"customers\",\"path\":[{\"index\":0}]}");
        assertThat(customer.path("children").toString()).contains("address", "self", "\"cycle\":true");
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"customers\",\"path\":[{\"index\":0},{\"field\":\"inheritedName\"}]}" ).path("value").asText()).isEqualTo("Ada");
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"tags\",\"path\":[{\"index\":1}]}" ).path("value").asText()).isEqualTo("two");
        assertThat(inspect(id, "inspect_variable", """
            {"variable":"grouped","path":[{"entry":0},{"index":0},{"field":"address"},{"field":"city"}]}
            """).path("value").asText()).isEqualTo("Bern");
        var arrayPage = inspect(id, "inspect_variable", "{\"variable\":\"counts\",\"offset\":1,\"limit\":1}");
        assertThat(arrayPage.path("children").get(0).path("value").asText()).isEqualTo("2");
        assertThat(arrayPage.path("hasMore").asBoolean()).isTrue();
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"counts\",\"path\":[{\"index\":100}]}" ).has("error")).isTrue();
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"__labVars\"}").has("error")).isTrue();
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"counts); System.exit(1); //\"}").has("error")).isTrue();
        var next = kernels.execute(id, "counts[0] = 42;");
        assertThat(next.executionCount()).isEqualTo(2); // inspections neither run cells nor advance their counter
        assertThat(inspect(id, "inspect_variable", "{\"variable\":\"counts\",\"path\":[{\"index\":0}]}").path("value").asText()).isEqualTo("42");
        String other = kernels.create();
        assertThat(inspect(other, "inspect_variable", "{\"variable\":\"customers\"}").has("error")).isTrue();
        kernels.remove(other);
        assertThatThrownBy(() -> inspect(other, "session_overview", "{}")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void searchesActualJarClassesAndReadsLibraryAndJdkSignaturesWithoutInitializingClasses() throws Exception {
        String id = kernels.create();
        var overview = inspect(id, "session_overview", "{}");
        assertThat(overview.path("libraries").toString()).contains("commons-csv.jar", "json.jar");
        var search = inspect(id, "search_library_classes", "{\"query\":\"csvparser\"}");
        assertThat(search.path("classes").toString()).contains("org.apache.commons.csv.CSVParser");
        var api = inspect(id, "inspect_class", "{\"className\":\"org.apache.commons.csv.CSVParser\",\"filter\":\"parse\"}");
        assertThat(api.toString()).doesNotContain("\"error\"");
        assertThat(api.path("signatures").toString()).contains("parse(", "java.io.Reader");
        var jdk = inspect(id, "inspect_class", "{\"className\":\"java.lang.String\",\"filter\":\"substring\"}");
        assertThat(jdk.path("signatures").size()).isEqualTo(2);
        assertThat(inspect(id, "inspect_class", "{\"className\":\"missing.SomeClass\"}").has("error")).isTrue();
        var definition = kernels.execute(id, """
            class UninitializedApi {
                static { if (true) throw new AssertionError("class must not be initialized"); }
                public static int available() { return 42; }
            }
            """);
        assertThat(definition.success()).as(definition.outputs().toString()).isTrue();
        var userApi = inspect(id, "inspect_class", "{\"className\":\"UninitializedApi\",\"filter\":\"available\"}");
        assertThat(userApi.path("signatures").toString()).as(userApi.toString()).contains("available()");
    }
}
