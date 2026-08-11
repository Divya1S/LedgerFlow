package com.ledgerflow.fraud.ai;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Runs the LLM assessment for flagged payments, strictly after the verdict
 * has committed and strictly off the consumer thread. Every failure mode
 * (feature disabled, no key, quota, bad output) degrades to "no
 * assessment"; verdicts and money never depend on this service.
 */
@Service
public class FraudAnalystService {

    private static final Logger log = LoggerFactory.getLogger(FraudAnalystService.class);

    private final ObjectProvider<LlmClient> llmProvider;
    private final FraudDataTools tools;
    private final JdbcClient jdbc;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fraud-analyst");
        thread.setDaemon(true);
        return thread;
    });

    public FraudAnalystService(ObjectProvider<LlmClient> llmProvider, FraudDataTools tools, JdbcClient jdbc) {
        this.llmProvider = llmProvider;
        this.tools = tools;
        this.jdbc = jdbc;
    }

    /** Fire-and-forget; called after the fraud verdict transaction commits. */
    public void assessAsync(UUID paymentId, String verdict, int score, String ruleHitsJson) {
        LlmClient llm = llmProvider.getIfAvailable();
        if (llm == null) {
            return; // feature disabled or no API key configured
        }
        executor.submit(() -> {
            try {
                assess(llm, paymentId, verdict, score, ruleHitsJson);
            } catch (Exception e) {
                log.warn("fraud AI assessment failed for payment {}: {}", paymentId, e.getMessage());
            }
        });
    }

    void assess(LlmClient llm, UUID paymentId, String verdict, int score, String ruleHitsJson) {
        String output = llm.complete(
                FraudAnalystPrompt.systemPrompt(),
                FraudAnalystPrompt.userPrompt(verdict, score, ruleHitsJson, paymentId.toString()),
                FraudAnalystPrompt.toolDefs(),
                tools);
        JsonNode assessment = FraudAnalystPrompt.parseAssessment(output);

        jdbc.sql("""
                        UPDATE fraud_decisions
                        SET ai_assessment = CAST(:assessment AS jsonb),
                            ai_model = :model,
                            ai_assessed_at = now()
                        WHERE payment_id = :paymentId
                        """)
                .param("assessment", assessment.toString())
                .param("model", llm.modelName())
                .param("paymentId", paymentId)
                .update();
        log.info("fraud AI assessment stored for payment {}: {}", paymentId,
                assessment.path("risk_level").asText());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
