package com.jobradar.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Minimal OpenRouter chat client. Deliberately small: the only things that matter here
 * are strict JSON back and the real dollar cost, which OpenRouter reports per response
 * so spend is measured rather than estimated.
 */
@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final String URL = "https://openrouter.ai/api/v1/chat/completions";

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final double budgetPerRun;
    private final DoubleAdder spent = new DoubleAdder();

    public OpenRouterClient(@Value("${jobradar.llm.api-key:}") String configuredKey,
                            @Value("${jobradar.llm.env-file:}") String envFile,
                            @Value("${jobradar.llm.model:deepseek/deepseek-v4-flash}") String model,
                            @Value("${jobradar.llm.budget-per-run:1.00}") double budgetPerRun) {
        this.apiKey = configuredKey.isBlank() ? readFromEnvFile(envFile) : configuredKey;
        this.model = model;
        this.budgetPerRun = budgetPerRun;
        this.http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(90).toMillis());
                }})
                .build();
        if (this.apiKey == null || this.apiKey.isBlank()) {
            log.warn("no OpenRouter API key found - scoring is disabled until one is provided");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public double spentThisRun() {
        return spent.sum();
    }

    public void resetSpend() {
        spent.reset();
    }

    public record Answer(String content, double cost, int promptTokens, int completionTokens) {}

    /**
     * @throws IllegalStateException when the call is unusable - a failure must be visible,
     *         never silently scored as zero.
     */
    public Answer complete(String systemPrompt, String userPrompt, int maxTokens) {
        if (!isConfigured()) throw new IllegalStateException("no OpenRouter API key configured");
        if (spent.sum() >= budgetPerRun) {
            throw new IllegalStateException("spend guard: $%.4f reached the $%.2f cap for this run"
                    .formatted(spent.sum(), budgetPerRun));
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", 0,
                // Reasoning tokens would eat the completion budget and return nothing useful here.
                "reasoning", Map.of("enabled", false),
                "response_format", Map.of("type", "json_object"),
                "usage", Map.of("include", true),
                "messages", java.util.List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));

        JsonNode response;
        try {
            String raw = http.post().uri(URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Title", "Job Radar")
                    .header("Content-Type", "application/json")
                    .body(json.writeValueAsString(body))
                    .retrieve().body(String.class);
            response = json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("OpenRouter call failed: " + e, e);
        }

        JsonNode choice = response.path("choices").path(0).path("message").path("content");
        if (choice.isMissingNode() || choice.asText("").isBlank()) {
            throw new IllegalStateException("OpenRouter returned no content: " + response.path("error"));
        }
        JsonNode usage = response.path("usage");
        double cost = usage.path("cost").asDouble(0);
        spent.add(cost);
        return new Answer(choice.asText(), cost,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }

    /** Reads OPENROUTER_API_KEY from a git-ignored .env file (jobradar.llm.env-file) or the environment. */
    private static String readFromEnvFile(String envFile) {
        if (envFile == null || envFile.isBlank()) return System.getenv("OPENROUTER_API_KEY");
        try {
            Path p = Path.of(envFile);
            if (!Files.exists(p)) return System.getenv("OPENROUTER_API_KEY");
            for (String line : Files.readAllLines(p)) {
                String t = line.trim();
                if (t.startsWith("OPENROUTER_API_KEY")) {
                    String v = t.substring(t.indexOf('=') + 1).trim();
                    return v.replaceAll("^[\"']|[\"']$", "");
                }
            }
        } catch (Exception e) {
            log.warn("could not read {}: {}", envFile, e.toString());
        }
        return System.getenv("OPENROUTER_API_KEY");
    }
}
