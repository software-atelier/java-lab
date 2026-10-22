package ch.software_atelier.java_lab.ai;

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
import java.util.concurrent.atomic.AtomicInteger;
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
    private volatile java.util.function.Function<JsonNode,String> responder;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            received = mapper.readTree(exchange.getRequestBody());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            titleHeader=exchange.getRequestHeaders().getFirst("X-Title");
            path=exchange.getRequestURI().getPath();query=exchange.getRequestURI().getQuery();
            byte[] response = (responder == null ? body : responder.apply(received)).getBytes(StandardCharsets.UTF_8);
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
        assertThat(received.has("provider")).isFalse();
        assertThat(provider("test-secret").name()).isEqualTo("OpenAI-compatible API");
    }

    @Test void openRouterSortsProvidersByThroughputByDefaultAndSupportsOverrides() throws Exception {
        body = response("int answer = 42;", "stop");
        for (String sort : new String[]{null, "", "throughput", "latency", "price", "default"}) {
            var properties = new AiProperties("key", "test/model", null, Duration.ofSeconds(2), 8192, true, 16, 64, sort);
            var router = new OpenAiCompatibleProvider(properties);
            // Keep real OpenRouter detection, but redirect HTTP traffic to the local test server.
            org.springframework.test.util.ReflectionTestUtils.setField(router, "endpoint",
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/chat/completions"));
            router.generate(messages());
            assertThat(titleHeader).isEqualTo("Java Lab");
            if ("default".equals(sort)) assertThat(received.has("provider")).isFalse();
            else assertThat(received.path("provider").path("sort").asText())
                .isEqualTo(sort == null || sort.isEmpty() ? "throughput" : sort);
        }
        assertThatThrownBy(() -> new AiProperties("key", "model", null, Duration.ofSeconds(2), 8192, true, 16, 64, "fastest"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AI_OPENROUTER_PROVIDER_SORT");
    }

    @Test void sqlGenerationNormalizesSqlWithoutJavaParsingAndRejectsTruncatedDrafts() throws Exception {
        body = response("```sql\nSELECT name FROM people WHERE active = TRUE;\n```", "stop");
        assertThat(provider("key").generateSql(messages(), null).code()).isEqualTo("SELECT name FROM people WHERE active = TRUE;");
        body = response("SELECT name FROM", "length");
        assertThatThrownBy(() -> provider("key").generateSql(messages(), null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("truncated");
    }

    @Test void textGenerationPreservesWhitespaceAndMarkdownFencesWithoutJavaValidation() throws Exception {
        for (String replacement : List.of("  <root/>\n", "{\"answer\":42}\n", "answer: 42\n", "```java\nint answer = 42;\n```\n")) {
            body = response(replacement, "stop");
            assertThat(provider("key").generateText(messages(), null, activity -> {}).code()).isEqualTo(replacement);
        }
        body = response("incomplete text", "length");
        assertThatThrownBy(() -> provider("key").generateText(messages(), null, activity -> {}))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("truncated");
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

    private String toolResponse(String arguments) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message",
            Map.of("tool_calls", List.of(Map.of("id", "call_1", "type", "function", "function",
                Map.of("name", "inspect_variable", "arguments", arguments))))))));
    }

    private CodeGeneration.Tools tools(java.util.function.Function<JsonNode,Object> execute) {
        return new CodeGeneration.Tools() {
            public List<Map<String,Object>> definitions() {
                return List.of(Map.of("type", "function", "function", Map.of("name", "inspect_variable", "parameters", Map.of("type", "object"))));
            }
            public Object execute(String name, JsonNode args) {
                assertThat(name).isEqualTo("inspect_variable");
                return execute.apply(args);
            }
        };
    }

    @Test void toolResultsAndAssistantCallsRoundTripBeforeReturningReplacementCode() throws Exception {
        body = toolResponse("{\"variable\":\"customers\",\"path\":[{\"index\":0},{\"field\":\"name\"}]}");
        var calls = new AtomicInteger();
        var tools = tools(args -> {
            assertThat(args.path("variable").asText()).isEqualTo("customers");
            assertThat(args.path("path").get(1).path("field").asText()).isEqualTo("name");
            calls.incrementAndGet();
            try { body = response("var name = customers.get(0).name;", "stop"); }
            catch (Exception e) { throw new AssertionError(e); }
            return Map.of("value", "Ada");
        });
        var activities = new java.util.ArrayList<String>();
        var result = provider("key").generate(messages(), tools, activities::add);
        assertThat(result.code()).isEqualTo("var name = customers.get(0).name;");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(received.path("tools").size()).isEqualTo(1);
        JsonNode history = received.path("messages");
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_1");
        assertThat(history.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(history.get(2).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(mapper.readTree(history.get(2).path("content").asText()).path("value").asText()).isEqualTo("Ada");
        assertThat(activities).anyMatch(s -> s.contains("Inspecting variable: customers (nested value)"))
            .anyMatch(s -> s.contains("request 2"));
        assertThat(String.join("\n", activities)).doesNotContain("Ada");
    }

    @Test void toolFailuresAreRecoverableAndDoNotExposeExceptionDetails() throws Exception {
        body = toolResponse("{}");
        var result = provider("key").generate(messages(), tools(args -> {
            try { body = response("int answer = 42;", "stop"); }
            catch (Exception e) { throw new AssertionError(e); }
            throw new IllegalStateException("private-password");
        }));
        assertThat(result.code()).isEqualTo("int answer = 42;");
        assertThat(received.path("messages").toString()).contains("Inspection unavailable").doesNotContain("private-password");
    }

    private OpenAiCompatibleProvider limitedProvider(int rounds, int calls) {
        return new OpenAiCompatibleProvider(new AiProperties("key", "test/model",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", Duration.ofSeconds(10), 8192, true, rounds, calls));
    }

    @Test void finishesWithCollectedFactsAfterTheConfiguredRoundLimit() throws Exception {
        String inspecting = toolResponse("{}");
        String finished = response("int answer = 42;", "stop");
        responder = request -> request.has("tools") ? inspecting : finished;
        var count = new AtomicInteger();
        var activities = new java.util.ArrayList<String>();
        var result = limitedProvider(2, 8).generate(messages(), tools(args -> {
            count.incrementAndGet(); return Map.of("value", "collected fact");
        }), activities::add);
        assertThat(result.code()).isEqualTo("int answer = 42;");
        assertThat(count.get()).isEqualTo(2);
        assertThat(received.has("tools")).isFalse();
        assertThat(received.has("tool_choice")).isFalse();
        assertThat(received.path("messages").toString()).contains("collected fact", "inspection phase is complete");
        assertThat(activities).anyMatch(s -> s.contains("Inspection budget reached"));
    }

    @Test void doesNotLoopIfTheModelKeepsCallingToolsAfterFinalization() throws Exception {
        body = toolResponse("{}");
        var count = new AtomicInteger();
        assertThatThrownBy(() -> limitedProvider(2, 8).generate(messages(), tools(args -> {
            count.incrementAndGet(); return Map.of("value", "same");
        }))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("after tools were disabled");
        assertThat(count.get()).isEqualTo(2);
        assertThat(received.has("tools")).isFalse();
        assertThatThrownBy(() -> provider("key").generate(messages())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unexpected tool response");
    }

    private String toolBatch(int size) throws Exception {
        var calls = java.util.stream.IntStream.range(0, size).mapToObj(i -> Map.of("id", "call_" + i, "type", "function",
            "function", Map.of("name", "inspect_variable", "arguments", "{}"))).toList();
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of("tool_calls", calls)))));
    }

    @Test void callBudgetSkipsExcessBatchMembersButRepliesToEveryAnnouncedCall() throws Exception {
        String batch = toolBatch(3), finished = response("int answer = 42;", "stop");
        responder = request -> request.has("tools") ? batch : finished;
        var count = new AtomicInteger();
        assertThat(limitedProvider(5, 2).generate(messages(), tools(args -> Map.of("value", count.incrementAndGet()))).code())
            .isEqualTo("int answer = 42;");
        assertThat(count.get()).isEqualTo(2);
        var replies = new java.util.ArrayList<JsonNode>();
        received.path("messages").forEach(m -> { if (m.path("role").asText().equals("tool")) replies.add(m); });
        assertThat(replies).hasSize(3);
        for (int i = 0; i < 3; i++) assertThat(replies.get(i).path("tool_call_id").asText()).isEqualTo("call_" + i);
        assertThat(mapper.readTree(replies.get(2).path("content").asText()).path("error").asText()).contains("not executed");
        assertThat(received.has("tools")).isFalse();
    }

    @Test void contextBudgetKeepsEarlierResultsAndFinalizesInsteadOfAborting() throws Exception {
        String batch = toolBatch(7), finished = response("int answer = 42;", "stop");
        responder = request -> request.has("tools") ? batch : finished;
        var count = new AtomicInteger();
        assertThat(limitedProvider(8, 20).generate(messages(), tools(args -> {
            count.incrementAndGet(); return Map.of("value", "x".repeat(23000));
        })).code()).isEqualTo("int answer = 42;");
        assertThat(count.get()).isEqualTo(6);
        var replies = new java.util.ArrayList<JsonNode>();
        received.path("messages").forEach(m -> { if (m.path("role").asText().equals("tool")) replies.add(m); });
        assertThat(replies).hasSize(7);
        assertThat(mapper.readTree(replies.get(0).path("content").asText()).path("value").asText()).hasSize(23000);
        assertThat(replies.get(5).path("content").asText()).contains("result was omitted");
        assertThat(replies.get(6).path("content").asText()).contains("not executed");
        assertThat(received.has("tools")).isFalse();
    }

    @Test void invalidArgumentsReturnToolErrorWithoutExecutingAndToolsCanBeDisabled() throws Exception {
        String invalid = toolResponse("not JSON"), finished = response("int result = 1;", "stop");
        responder = request -> request.has("tools") ? invalid : finished;
        var calls = new AtomicInteger();
        assertThat(limitedProvider(4, 1).generate(messages(), tools(args -> { calls.incrementAndGet(); return Map.of(); })).code())
            .isEqualTo("int result = 1;");
        assertThat(calls.get()).isZero();
        assertThat(received.path("messages").size()).isEqualTo(4); // user, one failed call, error result, final instruction
        responder = null;
        body = response("int result = 1;", "stop");
        var disabled = new OpenAiCompatibleProvider(new AiProperties("key", "test/model",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", Duration.ofSeconds(2), 8192, false));
        assertThat(disabled.generate(messages(), tools(args -> Map.of())).code()).isEqualTo("int result = 1;");
        assertThat(received.has("tools")).isFalse();
    }

    @Test void liveKernelValueReachesTheModelThroughTheCompleteGenerationFlow() throws Exception {
        try (var kernels = new ch.software_atelier.java_lab.KernelService()) {
            String kernel = kernels.create();
            var setup = kernels.execute(kernel, """
                class Customer { private String name = "Ada"; }
                var customers = List.of(new Customer());
                """);
            assertThat(setup.success()).isTrue();
            String first = toolResponse("{\"variable\":\"customers\",\"path\":[{\"index\":0},{\"field\":\"name\"}]}");
            String last = response("var firstCustomer = customers.get(0);", "stop");
            var observed = new AtomicInteger();
            responder = request -> {
                JsonNode history = request.path("messages");
                JsonNode tail = history.get(history.size() - 1);
                if (!tail.path("role").asText().equals("tool")) return first;
                try {
                    assertThat(mapper.readTree(tail.path("content").asText()).path("value").asText()).isEqualTo("Ada");
                } catch (Exception e) { throw new AssertionError(e); }
                observed.incrementAndGet();
                return last;
            };
            var service = new CodeGenerationService(provider("key"), List.of(), mapper, new SessionTools(kernels));
            String requestId = java.util.UUID.randomUUID().toString();
            var result = service.generate(new CodeGeneration.Request("Reuse the first customer", new CodeGeneration.Target("", null, null), List.of(), kernel, requestId));
            assertThat(result.code()).isEqualTo("var firstCustomer = customers.get(0);");
            assertThat(observed.get()).isEqualTo(1);
            assertThat(received.path("tools").size()).isEqualTo(4);
            assertThat(kernels.execute(kernel, "customers.size()").executionCount()).isEqualTo(2);
            var progress = service.progress(requestId);
            assertThat(progress.done()).isTrue();
            assertThat(progress.events()).extracting(GenerationProgress.Event::activity)
                .anyMatch(s -> s.contains("Inspecting variable: customers"));
            assertThat(progress.toString()).doesNotContain("Ada", "var firstCustomer");
            var endpoint = new AiController(service).progress(requestId);
            assertThat(endpoint.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(endpoint.getBody().done()).isTrue();
        }
    }

    private OpenAiCompatibleProvider timedProvider() {
        return new OpenAiCompatibleProvider(new AiProperties("key", "test/model",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1", Duration.ofSeconds(1), 8192));
    }

    @Test void readTimeoutExplainsTheConfiguredBudgetInsteadOfBlamingTheApiUrl() throws Exception {
        String late = response("int late = 1;", "stop");
        responder = request -> {
            try { Thread.sleep(1500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return late;
        };
        assertThatThrownBy(() -> timedProvider().generate(messages())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(504);
            assertThat(e.getReason()).contains("1 seconds", "AI_TIMEOUT", "including tool rounds").doesNotContain("AI_API_URL");
        });
    }

    @Test void toolRoundsShareTheOverallTimeoutRatherThanResettingIt() throws Exception {
        body = toolResponse("{}");
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> timedProvider().generate(messages(), tools(args -> {
            calls.incrementAndGet();
            try { Thread.sleep(1100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Map.of("value", "late");
        }))).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(504);
            assertThat(e.getReason()).contains("AI_TIMEOUT");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(received.path("messages").size()).isEqualTo(1);
    }

    @Test void connectionFailureIsReportedSeparatelyFromGenerationTimeout() throws Exception {
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        var unavailable = provider("key", "http://127.0.0.1:" + unusedPort + "/api/v1");
        assertThatThrownBy(() -> unavailable.generate(messages())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(502);
            assertThat(e.getReason()).contains("AI_API_URL", "network").doesNotContain("AI_TIMEOUT", "key");
        });
    }

    @Test void providerTimeoutIsDistinctFromTheLocalGenerationBudget() {
        status = 504; body = "{\"error\":\"sensitive provider detail\"}";
        assertThatThrownBy(() -> provider("key").generate(messages())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(504);
            assertThat(e.getReason()).contains("provider reported a timeout", "provider's own timeout").doesNotContain("sensitive provider detail");
        });
    }

    @Test void repairsIncompleteStopResponsesOnceAndReturnsPerRequestUsageDiagnostics() throws Exception {
        String incomplete = mapper.writeValueAsString(Map.of("model", "resolved/model-a", "usage", Map.of("prompt_tokens", 1200,
            "completion_tokens", 150, "completion_tokens_details", Map.of("reasoning_tokens", 30)),
            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "try (var rs = st.executeQuery(sql)) { while (rs unnecessary.")))));
        String corrected = mapper.writeValueAsString(Map.of("model", "resolved/model-b", "usage", Map.of("input_tokens", 1400,
            "output_tokens", 200, "output_tokens_details", Map.of("reasoning_tokens", 0)),
            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "int answer = 42;")))));
        var requests = new AtomicInteger();
        responder = request -> requests.incrementAndGet() == 1 ? incomplete : corrected;
        var activities = new java.util.ArrayList<String>();
        var result = provider("key").generate(messages(), null, activities::add);
        assertThat(result.code()).isEqualTo("int answer = 42;");
        assertThat(requests.get()).isEqualTo(2);
        assertThat(received.has("tools")).isFalse();
        assertThat(received.path("messages").toString()).contains("previous replacement was rejected", "complete replacement cell");
        assertThat(activities).anyMatch(s -> s.contains("requesting one complete correction"));
        assertThat(result.diagnostics()).hasSize(2);
        var first = result.diagnostics().get(0);
        assertThat(first.configuredModel()).isEqualTo("test/model");
        assertThat(first.reportedModel()).isEqualTo("resolved/model-a");
        assertThat(first.maxTokens()).isEqualTo(8192);
        assertThat(first.finishReason()).isEqualTo("stop");
        assertThat(first.inputTokens()).isEqualTo(1200);
        assertThat(first.outputTokens()).isEqualTo(150);
        assertThat(first.reasoningTokens()).isEqualTo(30);
        var second = result.diagnostics().get(1);
        assertThat(second.request()).isEqualTo(2);
        assertThat(second.reportedModel()).isEqualTo("resolved/model-b");
        assertThat(second.inputTokens()).isEqualTo(1400);
        assertThat(second.outputTokens()).isEqualTo(200);
        assertThat(second.reasoningTokens()).isZero();
    }

    @Test void reportsAndRepairsLengthResponsesEvenWhenTheirVisibleCodeLooksComplete() throws Exception {
        String truncated = response("int answer = 1;", "length"), complete = response("int answer = 42;", "stop");
        var requests = new AtomicInteger();
        responder = request -> requests.incrementAndGet() == 1 ? truncated : complete;
        var result = provider("key").generate(messages());
        assertThat(result.code()).isEqualTo("int answer = 42;");
        assertThat(result.diagnostics().get(0).finishReason()).isEqualTo("length");
        assertThat(result.diagnostics().get(0).inputTokens()).isNull();
        assertThat(result.diagnostics().get(0).reportedModel()).isNull();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test void checksSelectionEditsInTheOriginalCellAndRepairsOnlyTheReplacement() throws Exception {
        String incomplete = response("3 +", "stop"), complete = response("3 + 4", "stop");
        var requests = new AtomicInteger();
        responder = request -> requests.incrementAndGet() == 1 ? incomplete : complete;
        var observer = new CodeGeneration.Observer() { public void accept(String activity) {} };
        var target = new CodeGeneration.Target("int total = 1 + 2;", 12, 17);
        var result = provider("key").generate(messages(), null, observer, target);
        assertThat(result.code()).isEqualTo("3 + 4");
        assertThat(requests.get()).isEqualTo(2);
        assertThat(received.path("messages").toString()).contains("return ONLY that range's replacement");
    }

    @Test void refusesASecondIncompleteResponseAndKeepsDiagnosticsAvailableAfterFailure() throws Exception {
        body = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", "while (true) {"))),
            "usage", Map.of("prompt_tokens", -1, "completion_tokens", "unknown")));
        var progress = new GenerationProgress();
        var service = new CodeGenerationService(provider("key"), List.of(), mapper, null, progress);
        String id = java.util.UUID.randomUUID().toString();
        assertThatThrownBy(() -> service.generate(new CodeGeneration.Request("Edit", new CodeGeneration.Target("int original = 1;", null, null), List.of(), null, id)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("after one correction attempt").hasMessageContaining("original cell was kept");
        var snapshot = service.progress(id);
        assertThat(snapshot.done()).isTrue();
        assertThat(snapshot.diagnostics()).hasSize(2);
        assertThat(snapshot.diagnostics().get(0).finishReason()).isNull();
        assertThat(snapshot.diagnostics().get(0).inputTokens()).isNull();
        assertThat(snapshot.diagnostics().get(0).outputTokens()).isNull();
        assertThat(snapshot.toString()).doesNotContain("while (true)", "int original");
    }

    @Test void correctionUsesRemainingTimeInsteadOfStartingANewTimeout() throws Exception {
        String incomplete = response("while (true) {", "length"), complete = response("int answer = 42;", "stop");
        var requests = new AtomicInteger();
        responder = request -> {
            int number = requests.incrementAndGet();
            try { Thread.sleep(number == 1 ? 350 : 850); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return number == 1 ? incomplete : complete;
        };
        assertThatThrownBy(() -> timedProvider().generate(messages())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(504);
            assertThat(e.getReason()).contains("AI_TIMEOUT");
        });
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test void correctionAfterInspectionFinalizationDoesNotReenableToolsOrResetBudgets() throws Exception {
        String inspect = toolResponse("{}"), incomplete = response("while (true) {", "stop"), complete = response("int answer = 42;", "stop");
        var requests = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        responder = request -> switch (requests.incrementAndGet()) {
            case 1 -> inspect;
            case 2 -> incomplete;
            default -> complete;
        };
        var result = limitedProvider(1, 1).generate(messages(), tools(args -> {
            toolCalls.incrementAndGet(); return Map.of("value", "known");
        }));
        assertThat(requests.get()).isEqualTo(3);
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(received.has("tools")).isFalse();
        assertThat(result.code()).isEqualTo("int answer = 42;");
        assertThat(result.diagnostics()).hasSize(3);
    }
}
