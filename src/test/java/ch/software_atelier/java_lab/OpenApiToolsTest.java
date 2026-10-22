package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.ai.CodeGeneration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class OpenApiToolsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private CodeGeneration.Tools tools() {
        return new OpenApiTools(new NotebookStore(mapper, directory), mapper).forRequest();
    }

    private JsonNode read(CodeGeneration.Tools tools, String source, String pointer, int offset) {
        return mapper.valueToTree(tools.execute("read_openapi_spec", mapper.valueToTree(
            Map.of("source", source, "pointer", pointer, "offset", offset))));
    }

    @Test void readsYamlOperationsAndReferencedSchemasAndKeepsRequestSnapshot() throws Exception {
        Files.writeString(directory.resolve("api.yaml"), """
            openapi: 3.1.0
            info: {title: Pets, version: '1'}
            paths:
              /pets/{id}:
                get:
                  operationId: getPet
                  responses:
                    '200':
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/Pet'}
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    name: {type: string}
            """);
        var tools = tools();
        var operation = read(tools, "api.yaml", "/paths/~1pets~1{id}/get", 0);
        assertThat(operation.path("version").asText()).isEqualTo("3.1.0");
        assertThat(operation.path("content").asText()).contains("getPet", "#/components/schemas/Pet");
        Files.writeString(directory.resolve("api.yaml"), "{}");
        assertThat(read(tools, "api.yaml", "#/components/schemas/Pet", 0).path("content").asText()).contains("name", "string");
        assertThat(read(tools(), "api.yaml", "/components/schemas/Pet", 0).has("error")).isTrue();
    }

    @Test void paginatesJsonAndLoadsExternalReferenceDocuments() throws Exception {
        String text = "x".repeat(7000);
        Files.writeString(directory.resolve("schema.json"), mapper.writeValueAsString(Map.of("description", text)));
        var tools = tools();
        var first = read(tools, "schema.json", "/description", 0);
        var second = read(tools, "schema.json", "/description", first.path("nextOffset").asInt());
        var last = read(tools, "schema.json", "/description", second.path("nextOffset").asInt());
        assertThat(first.path("complete").asBoolean()).isFalse();
        assertThat(last.path("complete").asBoolean()).isTrue();
        String combined = first.path("content").asText() + second.path("content").asText() + last.path("content").asText();
        assertThat(mapper.readTree(combined).asText()).isEqualTo(text);
    }

    @Test void followsHttpRedirectsAndReportsEffectiveSourceForRelativeReferences() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/spec/api.json");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/spec/api.json", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"swagger\":\"2.0\",\"paths\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var tools = tools();
            var result = read(tools, base + "/redirect", "", 0);
            assertThat(result.path("version").asText()).isEqualTo("2.0");
            assertThat(result.path("source").asText()).isEqualTo(base + "/spec/api.json");
            assertThat(read(tools, base + "/redirect", "/paths", 0).path("content").asText()).isEqualTo("{}");
            assertThat(requests.get()).isEqualTo(1);
            assertThat(read(tools, base + "/missing", "", 0).has("error")).isTrue();
        } finally { server.stop(0); }
    }

    @Test void rejectsUnsafePathsMalformedDocumentsAndInvalidArguments() throws Exception {
        Files.writeString(directory.resolve("valid.yaml"), "openapi: 3.0.3\npaths: {}\n");
        Files.createSymbolicLink(directory.resolve("link.yaml"), directory.resolve("valid.yaml"));
        Files.writeString(directory.resolve("tag.yaml"), "!!java.net.URL ['https://example.com']");
        Files.writeString(directory.resolve("cycle.yaml"), "value: &a {recursive: *a}");
        Files.writeString(directory.resolve("large.json"), "x".repeat(TextFileStore.MAX_BYTES + 1));
        Files.writeString(directory.resolve("duplicate.yaml"), "paths: {}\npaths: {}\n");
        var tools = tools();
        for (String source : new String[]{"../outside.yaml", "/etc/passwd", "link.yaml", "tag.yaml", "cycle.yaml", "large.json", "duplicate.yaml", "file:///etc/passwd", "http://user:password@localhost/spec"}) {
            assertThat(read(tools, source, "", 0).has("error")).as(source).isTrue();
        }
        assertThat(read(tools, "valid.yaml", "/missing", 0).has("error")).isTrue();
        assertThat(read(tools, "valid.yaml", "invalid-pointer", 0).has("error")).isTrue();
        assertThat(read(tools, "valid.yaml", "", -1).has("error")).isTrue();
    }
}
