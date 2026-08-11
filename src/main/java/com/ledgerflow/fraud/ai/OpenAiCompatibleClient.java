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
 * LlmClient for any OpenAI-compatible chat-completions endpoint. The
 * headline use is a LOCAL Ollama server (no key, no quota, no cost:
 * http://localhost:11434/v1 with a tool-calling model such as qwen2.5),
 * but the same client speaks to Groq, Mistral or any other compatible
 * host by changing base-url, model and api-key.
 *
 * Same bounded loop as the Gemini client: the model asks for tools, the
 * executor answers, up to 8 rounds, then the final text is returned.
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final int MAX_TOOL_ROUNDS = 8;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final String apiKey; // blank for local Ollama
    private final Duration requestTimeout;

    public OpenAiCompatibleClient(String baseUrl, String model, String apiKey,
                                  Duration requestTimeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt,
                           List<ToolDef> tools, ToolExecutor executor) {
        ArrayNode messages = json.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JsonNode responseMessage = call(messages, tools);
            JsonNode toolCalls = responseMessage.path("tool_calls");

            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return responseMessage.path("content").asText();
            }

            // Replay the assistant turn verbatim, then answer each call.
            messages.add(responseMessage.deepCopy());
            for (JsonNode toolCall : toolCalls) {
                String toolName = toolCall.path("function").path("name").asText();
                JsonNode argsNode = toolCall.path("function").path("arguments");
                JsonNode args = parseArguments(argsNode);
                String result = executor.execute(toolName, args);
                log.debug("openai-compatible tool round {}: {}({})", round, toolName, args);

                ObjectNode toolMessage = json.createObjectNode();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", toolCall.path("id").asText(toolName));
                toolMessage.put("content", result);
                messages.add(toolMessage);
            }
        }
        throw new LlmException("model exceeded " + MAX_TOOL_ROUNDS + " tool rounds");
    }

    private JsonNode call(ArrayNode messages, List<ToolDef> tools) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("temperature", 0.2);
        if (!tools.isEmpty()) {
            ArrayNode toolsNode = json.createArrayNode();
            for (ToolDef tool : tools) {
                ObjectNode function = json.createObjectNode();
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", json.valueToTree(tool.parametersSchema()));
                ObjectNode wrapper = json.createObjectNode();
                wrapper.put("type", "function");
                wrapper.set("function", function);
                toolsNode.add(wrapper);
            }
            body.set("tools", toolsNode);
        }

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("LLM endpoint HTTP " + response.statusCode() + ": "
                        + truncate(response.body()));
            }
            JsonNode message = json.readTree(response.body())
                    .path("choices").path(0).path("message");
            if (message.isMissingNode()) {
                throw new LlmException("LLM endpoint returned no message: " + truncate(response.body()));
            }
            return message;
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM call interrupted", e);
        } catch (Exception e) {
            throw new LlmException("LLM call failed", e);
        }
    }

    private JsonNode parseArguments(JsonNode argsNode) {
        // OpenAI-style APIs send arguments as a JSON STRING; Ollama sends
        // an object directly. Accept both.
        if (argsNode.isObject()) {
            return argsNode;
        }
        try {
            return json.readTree(argsNode.asText("{}"));
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = json.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private String truncate(String value) {
        return value != null && value.length() > 400 ? value.substring(0, 400) : value;
    }
}
