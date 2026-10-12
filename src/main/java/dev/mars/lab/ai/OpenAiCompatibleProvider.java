package dev.mars.lab.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.regex.Pattern;

@Component
public class OpenAiCompatibleProvider implements CodeGenerationProvider {
    public static final String DEFAULT_API_URL = "https://openrouter.ai/api/v1";
    private static final Pattern FENCED_CODE = Pattern.compile("\\A```(?:java)?\\s*\\R([\\s\\S]*?)\\R?```\\s*\\z", Pattern.CASE_INSENSITIVE);
    private final AiProperties properties;
    private final RestClient client;
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
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(properties.timeout());
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override public boolean configured() {
        return properties.model()!=null&&!properties.model().isBlank()&&(!openRouter||hasKey());
    }
    private boolean hasKey(){return properties.apiKey()!=null&&!properties.apiKey().isBlank();}
    @Override public String name(){return openRouter?"OpenRouter":"OpenAI-compatible API";}
    @Override public String model() { return properties.model() == null ? "" : properties.model(); }

    @Override public CodeGeneration.Result generate(List<CodeGeneration.Message> messages) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Set AI_MODEL and AI_API_KEY if your API requires authentication, then restart Java Lab (OpenRouter requires an API key)");
        try {
            var request=client.post().uri(endpoint);
            if(hasKey())request.header("Authorization","Bearer "+properties.apiKey());
            if(openRouter)request.header("X-Title","Java Lab");
            JsonNode response = request
                .body(Map.of("model", model(), "messages", messages, "stream", false, "max_tokens", properties.maxTokens()))
                .retrieve().body(JsonNode.class);
            if (response == null || response.has("error")) throw invalidResponse("The AI API returned no usable code; try again");
            JsonNode choice = response.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText();
            if (finishReason.equals("length")) throw invalidResponse("Model output was truncated. Request a smaller edit or increase lab.ai.max-tokens");
            if (!finishReason.isBlank() && !finishReason.equals("stop")) throw invalidResponse("The model did not complete the edit; try another instruction or model");
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) throw invalidResponse("The model returned an empty response; try again");
            String code = content.asText().strip();
            var fenced = FENCED_CODE.matcher(code);
            if (fenced.matches()) code = fenced.group(1).strip();
            if (code.isBlank() || code.length() > 100_000) throw invalidResponse("Generated code is empty or exceeds the cell size limit");
            return new CodeGeneration.Result(code, model());
        } catch (RestClientResponseException e) {
            // Do not relay raw provider responses (which can contain prompts or credentials).
            int status = e.getStatusCode().value();
            String message = switch (status) {
                case 401, 403 -> "The AI API rejected authentication. Check AI_API_KEY";
                case 402 -> "The AI API requires payment or additional credits";
                case 429 -> "AI API rate limit reached; try again shortly";
                case 400, 404 -> "The AI API rejected the request. Check AI_MODEL and AI_API_URL";
                default -> "The AI API is unavailable; try again shortly";
            };
            throw new ResponseStatusException(status == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY, message);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach the AI API or the request timed out. Check AI_API_URL and try again");
        }
    }

    private ResponseStatusException invalidResponse(String message) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); }
}
