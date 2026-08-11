package com.ledgerflow.fraud.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini implementation of LlmClient over the generateContent REST API,
 * using the JDK HttpClient (no SDK dependency). Drives the function-calling
 * loop: model asks for a tool, we execute and answer with a
 * functionResponse turn, repeat until the model produces text.
 *
 * Bounded on purpose: max 8 tool round-trips, 30s per request, one retry
 * on 429/5xx. The caller treats any failure as "no assessment", never as
 * an error that matters to money.
 */
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final int MAX_TOOL_ROUNDS = 8;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt,
                           List<ToolDef> tools, ToolExecutor executor) {
        ArrayNode contents = json.createArrayNode();
        contents.add(turn("user", textPart(userPrompt)));

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JsonNode response = call(systemPrompt, contents, tools);
            JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
            if (parts.isMissingNode() || parts.isEmpty()) {
                throw new LlmException("Gemini returned no content parts: " + summarize(response));
            }

            JsonNode functionCall = null;
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("functionCall")) {
                    functionCall = part.get("functionCall");
                } else if (part.has("text")) {
                    text.append(part.get("text").asText());
                }
            }

            if (functionCall == null) {
                return text.toString();
            }

            String toolName = functionCall.path("name").asText();
            JsonNode args = functionCall.path("args");
            String toolResult = executor.execute(toolName, args);
            log.debug("gemini tool round {}: {}({})", round, toolName, args);

            // Replay the model's functionCall turn, then answer it.
            contents.add(turn("model", part("functionCall", functionCall)));
            ObjectNode functionResponse = json.createObjectNode();
            functionResponse.put("name", toolName);
            functionResponse.set("response", parseOrWrap(toolResult));
            contents.add(turn("user", part("functionResponse", functionResponse)));
        }
        throw new LlmException("Gemini exceeded " + MAX_TOOL_ROUNDS + " tool rounds");
    }

    private JsonNode call(String systemPrompt, ArrayNode contents, List<ToolDef> tools) {
        ObjectNode body = json.createObjectNode();
        body.set("systemInstruction", json.createObjectNode()
                .set("parts", json.createArrayNode().add(json.createObjectNode().put("text", systemPrompt))));
        body.set("contents", contents);

        if (!tools.isEmpty()) {
            ArrayNode declarations = json.createArrayNode();
            for (ToolDef tool : tools) {
                ObjectNode declaration = json.createObjectNode();
                declaration.put("name", tool.name());
                declaration.put("description", tool.description());
                declaration.set("parameters", json.valueToTree(tool.parametersSchema()));
                declarations.add(declaration);
            }
            body.set("tools", json.createArrayNode()
                    .add(json.createObjectNode().set("functionDeclarations", declarations)));
        }
        body.set("generationConfig", json.createObjectNode().put("temperature", 0.2));

        String url = "%s/models/%s:generateContent".formatted(baseUrl, model);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return json.readTree(response.body());
                }
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                if (retryable && attempt == 1) {
                    Thread.sleep(2000);
                    continue;
                }
                throw new LlmException("Gemini HTTP " + response.statusCode() + ": "
                        + truncate(response.body()));
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Gemini call interrupted", e);
            } catch (Exception e) {
                throw new LlmException("Gemini call failed", e);
            }
        }
        throw new LlmException("unreachable");
    }

    private ObjectNode turn(String role, ObjectNode part) {
        ObjectNode turn = json.createObjectNode();
        turn.put("role", role);
        turn.set("parts", json.createArrayNode().add(part));
        return turn;
    }

    private ObjectNode textPart(String text) {
        return json.createObjectNode().put("text", text);
    }

    private ObjectNode part(String key, JsonNode value) {
        ObjectNode part = json.createObjectNode();
        part.set(key, value);
        return part;
    }

    private JsonNode parseOrWrap(String toolResult) {
        try {
            JsonNode parsed = json.readTree(toolResult);
            return parsed.isObject() ? parsed : json.createObjectNode().set("result", parsed);
        } catch (Exception e) {
            return json.createObjectNode().put("result", toolResult);
        }
    }

    private String truncate(String value) {
        return value != null && value.length() > 400 ? value.substring(0, 400) : value;
    }

    private String summarize(JsonNode response) {
        return truncate(response.toString());
    }
}
