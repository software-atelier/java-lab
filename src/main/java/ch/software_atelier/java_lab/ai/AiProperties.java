package ch.software_atelier.java_lab.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("lab.ai")
public record AiProperties(String apiKey, String model, String apiUrl, Duration timeout, int maxTokens) {}
