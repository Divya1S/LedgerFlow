package com.ledgerflow.fraud.ai;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The LLM client exists only when the feature is switched on; otherwise no
 * LlmClient bean exists and FraudAnalystService silently does nothing.
 * AI is an optional layer, never a dependency.
 *
 * Two providers behind the same interface:
 *  - ollama (default): any OpenAI-compatible endpoint. Out of the box it
 *    points at a LOCAL Ollama server: no key, no quota, no cost.
 *  - gemini: Google's hosted API (needs GEMINI_API_KEY; free tier has
 *    tight rate limits).
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnProperty(name = "ledgerflow.ai.enabled", havingValue = "true")
    LlmClient llmClient(
            @Value("${ledgerflow.ai.provider:ollama}") String provider,
            @Value("${ledgerflow.ai.api-key:}") String apiKey,
            @Value("${ledgerflow.ai.model:}") String model,
            @Value("${ledgerflow.ai.base-url:}") String baseUrl) {
        return switch (provider) {
            case "gemini" -> {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "ledgerflow.ai.provider=gemini needs an API key (GEMINI_API_KEY)");
                }
                yield new GeminiClient(apiKey,
                        model.isBlank() ? "gemini-flash-latest" : model,
                        baseUrl.isBlank() ? "https://generativelanguage.googleapis.com/v1beta" : baseUrl);
            }
            case "ollama", "openai" -> new OpenAiCompatibleClient(
                    baseUrl.isBlank() ? "http://localhost:11434/v1" : baseUrl,
                    model.isBlank() ? "qwen2.5:7b" : model,
                    apiKey,
                    // Local models on laptop hardware think slowly; be patient.
                    Duration.ofSeconds(120));
            default -> throw new IllegalStateException("unknown ledgerflow.ai.provider: " + provider);
        };
    }
}
