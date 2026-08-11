package com.ledgerflow.fraud.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deterministic stand-in for Gemini: exercises the real tool loop (calls
 * every declared tool through the real executor) and returns a valid
 * assessment derived from the prompt. Lets CI prove the pipeline without
 * a network or a key.
 */
public class MockLlmClient implements LlmClient {

    private final ObjectMapper json = new ObjectMapper();
    public final List<String> toolCallsSeen = new CopyOnWriteArrayList<>();

    @Override
    public String modelName() {
        return "mock-analyst-1";
    }

    @Override
    public String complete(String systemPrompt, String userPrompt,
                           List<ToolDef> tools, ToolExecutor executor) {
        // Behave like a diligent model: gather evidence from every tool.
        String paymentId = userPrompt.replaceAll("(?s).*flagged payment ([0-9a-f-]{36}).*", "$1");
        for (ToolDef tool : tools) {
            try {
                var args = json.createObjectNode().put("payment_id", paymentId);
                executor.execute(tool.name(), args);
                toolCallsSeen.add(tool.name());
            } catch (Exception e) {
                toolCallsSeen.add(tool.name() + ":failed");
            }
        }
        String riskLevel = userPrompt.contains("REJECTED") ? "HIGH" : "MEDIUM";
        return """
                {"risk_level":"%s",
                 "summary":"Deterministic mock assessment for payment %s based on rule engine output.",
                 "key_factors":["rule engine flagged this payment","mock analysis"],
                 "recommended_action":"Route to a human analyst."}
                """.formatted(riskLevel, paymentId);
    }
}
