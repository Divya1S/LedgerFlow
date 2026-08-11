package com.ledgerflow.fraud.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Gemini client exists only when the feature is switched on AND a key
 * is configured; otherwise no LlmClient bean exists and FraudAnalystService
 * silently does nothing. AI is an optional layer, never a dependency.
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnProperty(name = "ledgerflow.ai.enabled", havingValue = "true")
    LlmClient geminiClient(@Value("${ledgerflow.ai.api-key:}") String apiKey,
                           @Value("${ledgerflow.ai.model:gemini-2.5-flash}") String model,
                           @Value("${ledgerflow.ai.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ledgerflow.ai.enabled=true but no API key set (GEMINI_API_KEY)");
        }
        return new GeminiClient(apiKey, model, baseUrl);
    }
}
