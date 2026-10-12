package dev.mars.lab.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OpenAiCompatibleProviderTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile JsonNode received;
    private volatile String authorization;
    private volatile String path;
    private volatile String query;
    private volatile String titleHeader;
    private volatile int status;
    private volatile String body;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            received = mapper.readTree(exchange.getRequestBody());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            titleHeader=exchange.getRequestHeaders().getFirst("X-Title");
            path=exchange.getRequestURI().getPath();query=exchange.getRequestURI().getQuery();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        status = 200;
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiCompatibleProvider provider(String key) {
        return provider(key,"http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1");
    }
    private OpenAiCompatibleProvider provider(String key,String url) {
        return new OpenAiCompatibleProvider(new AiProperties(key,"test/model",url,Duration.ofSeconds(2),8192));
    }
    private List<CodeGeneration.Message> messages() { return List.of(new CodeGeneration.Message("user", "Write Java")); }
    private String response(String code, String reason) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", reason, "message", Map.of("content", code)))));
    }

    @Test void sendsServerCredentialsAndConfiguredModelAndNormalizesFencedCode() throws Exception {
        body = response("```java\nSystem.out.println(42);\n```", "stop");
        var result = provider("test-secret").generate(messages());
        assertThat(result.code()).isEqualTo("System.out.println(42);");
        assertThat(result.model()).isEqualTo("test/model");
        assertThat(authorization).isEqualTo("Bearer test-secret");
        assertThat(received.path("model").asText()).isEqualTo("test/model");
        assertThat(received.path("messages").get(0).path("content").asText()).isEqualTo("Write Java");
        assertThat(received.path("stream").asBoolean()).isFalse();
        assertThat(received.path("max_tokens").asInt()).isEqualTo(8192);
        assertThat(path).isEqualTo("/api/v1/chat/completions");
        assertThat(titleHeader).isNull();
        assertThat(provider("test-secret").name()).isEqualTo("OpenAI-compatible API");
    }

    @Test void missingConfigurationDoesNotMakeARequest() {
        var missing=new OpenAiCompatibleProvider(new AiProperties("","test/model",null,Duration.ofSeconds(2),8192));
        assertThat(missing.name()).isEqualTo("OpenRouter");
        assertThatThrownBy(() -> missing.generate(messages())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("AI_API_KEY");
        assertThat(received).isNull();
    }

    @Test void acceptsTrailingSlashesAndCompleteEndpointsWithoutDuplicatingTheRoute() throws Exception {
        body=response("log(\"ok\");","stop");
        String base="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1";
        for(String url:List.of(base+"/",base+"/chat/completions",base+"/chat/completions/",base+"?api-version=test")) {
            assertThat(provider("key",url).generate(messages()).code()).isEqualTo("log(\"ok\");");
            assertThat(path).isEqualTo("/api/v1/chat/completions");
        }
        assertThat(query).isEqualTo("api-version=test");
    }

    @Test void supportsUnauthenticatedCompatibleApisWithoutSendingAnEmptyBearerHeader() throws Exception {
        body=response("log(\"local\");","stop");
        var local=provider("");assertThat(local.configured()).isTrue();
        assertThat(local.generate(messages()).code()).isEqualTo("log(\"local\");");
        assertThat(authorization).isNull();
    }

    @Test void openRouterRemainsTheDefaultAndInvalidUrlsFailClearly() {
        var defaults=new OpenAiCompatibleProvider(new AiProperties("key","model","",Duration.ofSeconds(2),8192));
        assertThat(defaults.name()).isEqualTo("OpenRouter");assertThat(defaults.configured()).isTrue();
        for(String url:List.of("not a url","ftp://example.com/v1","/v1","https://user:secret@example.com/v1")) {
            assertThatThrownBy(()->provider("key",url)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AI_API_URL");
        }
    }

    @Test void reportsRateLimitWithoutLeakingProviderResponse() {
        status = 429; body = "{\"error\":{\"message\":\"sensitive prompt test-secret\"}}";
        assertThatThrownBy(() -> provider("test-secret").generate(messages())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(429);
            assertThat(e.getReason()).contains("rate limit").doesNotContain("test-secret", "sensitive prompt");
        });
    }

    @Test void rejectsTruncatedEmptyAndMalformedModelResponses() throws Exception {
        for (String response : List.of(response("int broken =", "length"), response("int broken =", "error"), response("", "stop"), "{}", "{\"error\":{}}")) {
            body = response;
            assertThatThrownBy(() -> provider("test-secret").generate(messages())).isInstanceOf(ResponseStatusException.class);
        }
    }
}
