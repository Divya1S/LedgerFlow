package com.ledgerflow.fraud.ai;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal provider-agnostic LLM interface: one completion with tool
 * calling. The client drives the tool loop internally (model asks for a
 * tool, the executor answers, repeat) and returns the model's final text.
 * Keeping the surface this small makes providers swappable and the mock
 * for tests trivial.
 */
public interface LlmClient {

    /** JSON-schema described tool the model may call. */
    record ToolDef(String name, String description, Map<String, Object> parametersSchema) {
    }

    /** Answers the model's tool calls with JSON strings. Read-only by contract. */
    interface ToolExecutor {
        String execute(String toolName, JsonNode arguments);
    }

    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmException(String message) {
            super(message);
        }
    }

    String modelName();

    String complete(String systemPrompt, String userPrompt,
                    List<ToolDef> tools, ToolExecutor executor) throws LlmException;
}
