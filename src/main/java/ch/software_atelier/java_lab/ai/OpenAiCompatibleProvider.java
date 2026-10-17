package ch.software_atelier.java_lab.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.regex.Pattern;

@Component
public class OpenAiCompatibleProvider implements CodeGenerationProvider {
    public static final String DEFAULT_API_URL = "https://openrouter.ai/api/v1";
    private static final Pattern FENCED_CODE = Pattern.compile("\\A```(?:java)?\\s*\\R([\\s\\S]*?)\\R?```\\s*\\z", Pattern.CASE_INSENSITIVE);
    private final AiProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;
    private final boolean openRouter;

    public OpenAiCompatibleProvider(AiProperties properties) {
        this.properties = properties;
        String url=properties.apiUrl()==null||properties.apiUrl().isBlank()?DEFAULT_API_URL:properties.apiUrl().strip();
        URI base;
        try { base=URI.create(url); }
        catch(IllegalArgumentException e){throw new IllegalArgumentException("AI_API_URL must be a valid HTTP(S) API URL");}
        if(!("http".equalsIgnoreCase(base.getScheme())||"https".equalsIgnoreCase(base.getScheme()))||base.getHost()==null||base.getFragment()!=null||base.getUserInfo()!=null)
            throw new IllegalArgumentException("AI_API_URL must be an absolute HTTP(S) URL without a fragment or embedded credentials");
        openRouter="openrouter.ai".equalsIgnoreCase(base.getHost());
        String path=base.getRawPath()==null?"":base.getRawPath().replaceAll("/+$","");
        endpoint=UriComponentsBuilder.fromUri(base).replacePath(path.endsWith("/chat/completions")?path:path+"/chat/completions").build(true).toUri();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override public boolean configured() {
        return properties.model()!=null&&!properties.model().isBlank()&&(!openRouter||hasKey());
    }
    private boolean hasKey(){return properties.apiKey()!=null&&!properties.apiKey().isBlank();}
    @Override public String name(){return openRouter?"OpenRouter":"OpenAI-compatible API";}
    @Override public String model() { return properties.model() == null ? "" : properties.model(); }

    @Override public CodeGeneration.Result generate(List<CodeGeneration.Message> messages) {
        return generate(messages, null);
    }

    @Override public CodeGeneration.Result generate(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools) {
        return generate(messages, tools, activity -> {});
    }

    @Override public CodeGeneration.Result generate(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools, java.util.function.Consumer<String> progress) {
        return generate(messages, tools, new CodeGeneration.Observer() {
            public void accept(String activity) { progress.accept(activity); }
        }, null);
    }

    @Override public CodeGeneration.Result generate(List<CodeGeneration.Message> messages, CodeGeneration.Tools tools,
                                                    CodeGeneration.Observer progress, CodeGeneration.Target target) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Set AI_MODEL and AI_API_KEY if your API requires authentication, then restart Java Lab (OpenRouter requires an API key)");
        if (!properties.toolsEnabled()) tools = null;
        var conversation = new ArrayList<>(messages);
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        int toolCalls = 0, resultChars = 0, inspectionRounds = 0, modelRequests = 0;
        boolean finalizing = false, contextBudgetExhausted = false;
        boolean correctionAttempted = false;
        var diagnostics = new ArrayList<CodeGeneration.ResponseDiagnostics>();
        try {
            while (true) {
                checkDeadline(deadline);
                if (!finalizing && tools != null && (inspectionRounds >= properties.maxToolRounds()
                    || toolCalls >= properties.maxToolCalls() || contextBudgetExhausted)) {
                    finalizing = true;
                    progress.accept("Inspection budget reached (" + inspectionRounds + " rounds, " + toolCalls + " calls); generating code from the collected context…");
                    conversation.add(new CodeGeneration.Message("system", """
                        The inspection phase is complete and tools are no longer available. Generate the final
                        replacement Java code now using the original edit target, instruction, and facts already
                        collected. Do not request any further tool calls, repeat inspections, or invent missing
                        values or APIs. Return ONLY the requested Java replacement code.
                        """));
                }
                modelRequests++;
                progress.accept(finalizing ? "Generating final code from collected inspection context (request " + modelRequests + ")…"
                    : "Waiting for model response (request " + modelRequests + ")…");
                var pendingDiagnostics = new CodeGeneration.ResponseDiagnostics(modelRequests, model(), null, properties.maxTokens(), null, null, null, null);
                diagnostics.add(pendingDiagnostics);
                progress.response(pendingDiagnostics);
                var factory = new JdkClientHttpRequestFactory(httpClient);
                factory.setReadTimeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                var request = RestClient.builder().requestFactory(factory).build().post().uri(endpoint);
                if (hasKey()) request.header("Authorization", "Bearer " + properties.apiKey());
                if (openRouter) request.header("X-Title", "Java Lab");
                var payload = new LinkedHashMap<String,Object>();
                payload.put("model", model()); payload.put("messages", conversation);
                payload.put("stream", false); payload.put("max_tokens", properties.maxTokens());
                if (tools != null && !finalizing) { payload.put("tools", tools.definitions()); payload.put("tool_choice", "auto"); }
                JsonNode response = request.body(payload).retrieve().body(JsonNode.class);
                if (response == null || response.has("error")) throw invalidResponse("The AI API returned no usable code; try again");
                JsonNode choice = response.path("choices").path(0);
                String finishReason = choice.path("finish_reason").asText();
                var responseDiagnostics = responseDiagnostics(response, finishReason, modelRequests);
                diagnostics.set(diagnostics.size() - 1, responseDiagnostics);
                progress.response(responseDiagnostics);
                checkDeadline(deadline);
                JsonNode message = choice.path("message");
                JsonNode calls = message.path("tool_calls");
                boolean truncated = finishReason.equals("length");
                if (!truncated && calls.isArray() && !calls.isEmpty()) {
                    if (finalizing) throw invalidResponse(correctionAttempted
                        ? "The model requested tools during the final correction. Your original cell was kept; try a different model"
                        : "The model requested more inspection after tools were disabled. Try a different model or increase AI_MAX_TOOL_ROUNDS / AI_MAX_TOOL_CALLS");
                    if (tools == null || (!finishReason.isBlank() && !finishReason.equals("tool_calls") && !finishReason.equals("stop")))
                        throw invalidResponse("Unexpected tool response from the AI API");
                    if (calls.size() > 256) throw invalidResponse("The AI API returned too many tool calls in one response");
                    var parsed = new ArrayList<CodeGeneration.ToolCall>();
                    var ids = new HashSet<String>();
                    for (JsonNode call : calls) {
                        String id = call.path("id").asText();
                        JsonNode function = call.path("function");
                        if (id.isBlank() || id.length() > 200 || !ids.add(id) || !call.path("type").asText().equals("function")
                            || !function.path("name").isTextual() || function.path("name").asText().length() > 100
                            || !function.path("arguments").isTextual() || function.path("arguments").asText().length() > 8000)
                            throw invalidResponse("The AI API returned an invalid tool call");
                        parsed.add(new CodeGeneration.ToolCall(id, "function", new CodeGeneration.Function(function.path("name").asText(), function.path("arguments").asText())));
                    }
                    String commentary = message.path("content").isTextual() ? message.path("content").asText() : null;
                    if (commentary != null && commentary.length() > 100000) throw invalidResponse("AI tool commentary exceeds the context limit");
                    conversation.add(new CodeGeneration.Message("assistant", commentary, parsed, null));
                    inspectionRounds++;
                    for (var call : parsed) {
                        checkDeadline(deadline);
                        String json;
                        if (toolCalls >= properties.maxToolCalls() || contextBudgetExhausted) {
                            json = "{\"error\":\"Inspection budget exhausted. This tool was not executed; finish using collected facts.\"}";
                        } else {
                            Object result;
                            // Invalid/failed calls also consume budget, so malformed requests cannot loop forever.
                            toolCalls++;
                            try {
                                JsonNode args = mapper.readTree(call.function().arguments());
                                progress.accept(toolActivity(call.function().name(), args));
                                result = tools.execute(call.function().name(), args);
                                progress.accept("Inspection finished; preparing the next model request…");
                            } catch (Exception e) {
                                progress.accept("Inspection unavailable; continuing with the available context…");
                                result = Map.of("error", "Inspection unavailable or invalid arguments; do not assume a value. Check the session and selectors.");
                            }
                            json = mapper.writeValueAsString(result);
                            if (json.length() > 24000) json = "{\"error\":\"Tool result exceeds 24000 characters; use a narrower query or smaller page\"}";
                            if (resultChars + json.length() > 120000) {
                                contextBudgetExhausted = true;
                                json = "{\"error\":\"Inspection context budget exhausted. This result was omitted; finish using previously collected facts.\"}";
                            } else {
                                resultChars += json.length();
                                contextBudgetExhausted = resultChars >= 120000;
                            }
                        }
                        // Every announced call receives a result, including skipped calls in an oversized batch.
                        conversation.add(new CodeGeneration.Message("tool", json, null, call.id()));
                    }
                    continue;
                }
                if (!truncated && !finishReason.isBlank() && !finishReason.equals("stop")) throw invalidResponse("The model did not complete the edit; try another instruction or model");
                JsonNode content = message.path("content");
                if (!truncated && (!content.isTextual() || content.asText().isBlank())) throw invalidResponse("The model returned an empty response; try again");
                String code = content.isTextual() ? content.asText().strip() : "";
                var fenced = FENCED_CODE.matcher(code);
                if (fenced.matches()) code = fenced.group(1).strip();
                if (code.length() > 100_000) throw invalidResponse("Generated code exceeds the cell size limit");
                progress.accept("Checking Java snippet completeness without executing code…");
                String problem = truncated ? "The provider reported finish_reason=length; the response was truncated."
                    : JavaSnippetValidator.problem(target, code, () -> checkDeadline(deadline));
                checkDeadline(deadline);
                if (problem != null) {
                    if (correctionAttempted) throw invalidResponse("The model returned truncated, incomplete, or malformed Java again after one correction attempt. Your original cell was kept. Check the model diagnostics and output token limit");
                    correctionAttempted = true;
                    finalizing = true;
                    progress.accept("Incomplete Java response rejected; requesting one complete correction…");
                    if (!code.isBlank()) conversation.add(new CodeGeneration.Message("assistant", code));
                    conversation.add(new CodeGeneration.Message("system", """
                        The previous replacement was rejected before application: %s
                        Correct it once now. Preserve the original user's intent and useful existing code, but
                        produce a complete, concise Java replacement with no unfinished loops, strings, blocks,
                        or placeholders and no unused resources. The resulting whole cell must be structurally
                        complete. For a selected range, return ONLY that range's replacement, not the whole cell.
                        Otherwise return the complete replacement cell. Return ONLY Java code, not an explanation
                        or continuation of the previous answer. Tools are unavailable; use already collected facts
                        and do not invent missing values or APIs.
                        """.formatted(problem)));
                    continue;
                }
                progress.accept("Replacement code is structurally complete; no code was executed.");
                return new CodeGeneration.Result(code, model(), List.copyOf(diagnostics));
            }
        } catch (RestClientResponseException e) {
            // Do not relay raw provider responses (which can contain prompts or credentials).
            int status = e.getStatusCode().value();
            String message = switch (status) {
                case 401, 403 -> "The AI API rejected authentication. Check AI_API_KEY";
                case 402 -> "The AI API requires payment or additional credits";
                case 429 -> "AI API rate limit reached; try again shortly";
                case 408, 504 -> "The AI provider reported a timeout. Try again, use a faster model, or reduce context; increasing AI_TIMEOUT does not change the provider's own timeout";
                case 400, 404 -> "The AI API rejected the request. Check AI_MODEL and AI_API_URL; for models without tool support set AI_TOOLS_ENABLED=false";
                default -> "The AI API is unavailable; try again shortly";
            };
            HttpStatus resultStatus = status == 429 ? HttpStatus.TOO_MANY_REQUESTS
                : status == 408 || status == 504 ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(resultStatus, message);
        } catch (RestClientException e) {
            if (causedBy(e, java.net.http.HttpConnectTimeoutException.class))
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Connecting to the AI API timed out. Check AI_API_URL, network access, and the provider's availability");
            if (causedBy(e, java.net.http.HttpTimeoutException.class) || causedBy(e, java.net.SocketTimeoutException.class)
                || causedBy(e, java.util.concurrent.TimeoutException.class)) throw timeout();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not communicate with the AI API. Check AI_API_URL, network access, and the provider's availability");
        } catch (java.io.IOException e) {
            throw invalidResponse("Could not process the AI tool response");
        }
    }

    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw invalidResponse("AI generation was interrupted; try again");
        if (System.nanoTime() >= deadline) throw timeout();
    }

    private ResponseStatusException timeout() {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI generation exceeded the configured timeout of "
            + properties.timeout().toSeconds() + " seconds (including tool rounds). Increase AI_TIMEOUT (e.g. 10m), use a faster model, or include less context");
    }

    private CodeGeneration.ResponseDiagnostics responseDiagnostics(JsonNode response, String finishReason, int request) {
        JsonNode usage = response.path("usage");
        Long input = tokenCount(usage.path("prompt_tokens"));
        if (input == null) input = tokenCount(usage.path("input_tokens"));
        Long output = tokenCount(usage.path("completion_tokens"));
        if (output == null) output = tokenCount(usage.path("output_tokens"));
        Long reasoning = tokenCount(usage.path("completion_tokens_details").path("reasoning_tokens"));
        if (reasoning == null) reasoning = tokenCount(usage.path("output_tokens_details").path("reasoning_tokens"));
        if (reasoning == null) reasoning = tokenCount(usage.path("reasoning_tokens"));
        String reported = response.path("model").asText();
        if (!reported.matches("[A-Za-z0-9][A-Za-z0-9._:/@+ -]{0,199}")) reported = null;
        if (!finishReason.matches("[A-Za-z0-9_-]{1,64}")) finishReason = null;
        return new CodeGeneration.ResponseDiagnostics(request, model(), reported, properties.maxTokens(), finishReason, input, output, reasoning);
    }

    private Long tokenCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }

    private String toolActivity(String name, JsonNode args) {
        return switch (name) {
            case "session_overview" -> "Inspecting session variables, imports, methods, and libraries…";
            case "inspect_variable" -> "Inspecting variable" + safeIdentifier(args, "variable")
                + (args != null && args.path("path").isArray() && !args.path("path").isEmpty() ? " (nested value)" : "") + "…";
            case "search_library_classes" -> "Searching loaded library classes…";
            case "inspect_class" -> "Inspecting class API" + safeIdentifier(args, "className") + "…";
            default -> "Running an inspection tool…";
        };
    }

    private String safeIdentifier(JsonNode args, String field) {
        String value = args == null ? "" : args.path(field).asText();
        return value.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]{0,119}") ? ": " + value : "";
    }

    private boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (int i = 0; error != null && i < 32; i++, error = error.getCause())
            if (type.isInstance(error)) return true;
        return false;
    }

    private ResponseStatusException invalidResponse(String message) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); }
}
