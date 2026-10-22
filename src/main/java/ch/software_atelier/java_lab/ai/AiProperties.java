package ch.software_atelier.java_lab.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("lab.ai")
public record AiProperties(String apiKey, String model, String apiUrl, Duration timeout, int maxTokens, boolean toolsEnabled,
                           int maxToolRounds, int maxToolCalls, String openrouterProviderSort) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AiProperties {
        openrouterProviderSort = openrouterProviderSort == null || openrouterProviderSort.isBlank()
            ? "throughput" : openrouterProviderSort.strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("throughput", "latency", "price", "default").contains(openrouterProviderSort))
            throw new IllegalArgumentException("AI_OPENROUTER_PROVIDER_SORT must be throughput, latency, price, or default");
        if (maxToolRounds < 1 || maxToolRounds > 64) throw new IllegalArgumentException("AI_MAX_TOOL_ROUNDS must be between 1 and 64");
        if (maxToolCalls < 1 || maxToolCalls > 256) throw new IllegalArgumentException("AI_MAX_TOOL_CALLS must be between 1 and 256");
    }

    public AiProperties(String apiKey, String model, String apiUrl, Duration timeout, int maxTokens, boolean toolsEnabled,
                        int maxToolRounds, int maxToolCalls) {
        this(apiKey, model, apiUrl, timeout, maxTokens, toolsEnabled, maxToolRounds, maxToolCalls, null);
    }

    public AiProperties(String apiKey, String model, String apiUrl, Duration timeout, int maxTokens, boolean toolsEnabled) {
        this(apiKey, model, apiUrl, timeout, maxTokens, toolsEnabled, 16, 64);
    }

    public AiProperties(String apiKey, String model, String apiUrl, Duration timeout, int maxTokens) {
        this(apiKey, model, apiUrl, timeout, maxTokens, true);
    }
}
