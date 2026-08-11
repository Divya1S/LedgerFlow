package com.ledgerflow.fraud.ai;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LLM eval: runs the REAL Gemini model against the golden cases in
 * ai-eval/eval-cases.json, with every tool response fixed by the case, so
 * it measures prompt + model quality in isolation.
 *
 * Scored on: risk-level accuracy (model's level in the case's accepted
 * set, and never in the forbidden set) and signal recall (the case's
 * required keywords appear in the summary or key factors).
 *
 * Excluded from CI (tag llm-eval; needs a key and costs a few cents). Run:
 *   GEMINI_API_KEY=... mvn test -Dtest=FraudAnalystEvalTest \
 *     -Dsurefire.excludedGroups= -Dgroups=llm-eval
 * Recorded results live in docs/ai-fraud-copilot.md.
 */
@Tag("llm-eval")
class FraudAnalystEvalTest {

    private final ObjectMapper json = new ObjectMapper();

    private record CaseResult(String name, boolean riskPass, boolean signalPass,
                              String gotRisk, String detail) {
    }

    @Test
    void goldenCasesScorecard() throws Exception {
        JsonNode suite = json.readTree(getClass().getResourceAsStream("/ai-eval/eval-cases.json"));
        LlmClient llm = buildClient();

        List<CaseResult> results = new ArrayList<>();
        long pauseMs = Long.parseLong(System.getenv().getOrDefault("EVAL_PAUSE_MS", "25000"));
        for (JsonNode caseNode : suite.get("cases")) {
            results.add(runCase(llm, caseNode));
            Thread.sleep(pauseMs); // free-tier RPM budgets are tiny; pace the suite
        }

        long riskPasses = results.stream().filter(CaseResult::riskPass).count();
        long signalPasses = results.stream().filter(CaseResult::signalPass).count();
        double riskAccuracy = (double) riskPasses / results.size();
        double signalRecall = (double) signalPasses / results.size();

        System.out.println("\n=== Fraud analyst eval scorecard (" + llm.modelName() + ") ===");
        for (CaseResult r : results) {
            System.out.printf("%-38s risk:%-4s signals:%-4s got=%s%s%n",
                    r.name(), r.riskPass() ? "PASS" : "FAIL", r.signalPass() ? "PASS" : "FAIL",
                    r.gotRisk(), r.detail().isEmpty() ? "" : " (" + r.detail() + ")");
        }
        System.out.printf("risk-level accuracy: %d/%d (%.0f%%)%n", riskPasses, results.size(), riskAccuracy * 100);
        System.out.printf("signal recall:       %d/%d (%.0f%%)%n", signalPasses, results.size(), signalRecall * 100);

        // Regression floors, not targets: the docs report the exact measured
        // scores per model; dropping below these floors means the prompt or
        // the pipeline broke, not that the model had a mediocre day.
        assertThat(riskAccuracy).as("risk-level accuracy").isGreaterThanOrEqualTo(0.5);
        assertThat(signalRecall).as("signal recall").isGreaterThanOrEqualTo(0.5);
    }

    /**
     * AI_PROVIDER=ollama (default, local, free) or gemini (needs
     * GEMINI_API_KEY). AI_MODEL/AI_BASE_URL override the defaults.
     */
    private LlmClient buildClient() {
        String provider = System.getenv().getOrDefault("AI_PROVIDER", "ollama");
        if ("gemini".equals(provider)) {
            String key = System.getenv("GEMINI_API_KEY");
            org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank(),
                    "AI_PROVIDER=gemini needs GEMINI_API_KEY");
            return new GeminiClient(key,
                    System.getenv().getOrDefault("AI_MODEL", "gemini-flash-latest"),
                    "https://generativelanguage.googleapis.com/v1beta");
        }
        return new OpenAiCompatibleClient(
                System.getenv().getOrDefault("AI_BASE_URL", "http://localhost:11434/v1"),
                System.getenv().getOrDefault("AI_MODEL", "qwen2.5:7b"),
                System.getenv().getOrDefault("AI_API_KEY", ""),
                java.time.Duration.ofSeconds(180));
    }

    private CaseResult runCase(LlmClient llm, JsonNode caseNode) {
        String name = caseNode.get("name").asText();
        JsonNode tools = caseNode.get("tools");
        LlmClient.ToolExecutor stub = (toolName, args) ->
                tools.has(toolName) ? tools.get(toolName).toString() : "{\"error\":\"no such tool\"}";

        try {
            String output = llm.complete(
                    FraudAnalystPrompt.systemPrompt(),
                    FraudAnalystPrompt.userPrompt(
                            caseNode.get("verdict").asText(),
                            caseNode.get("score").asInt(),
                            caseNode.get("rule_hits").toString(),
                            caseNode.get("tools").get("get_payment_details").get("payment_id").asText()),
                    FraudAnalystPrompt.toolDefs(),
                    stub);
            JsonNode assessment = FraudAnalystPrompt.parseAssessment(output);
            String risk = assessment.get("risk_level").asText();

            boolean accepted = false;
            for (JsonNode level : caseNode.get("accepted_risk_levels")) {
                accepted |= level.asText().equals(risk);
            }
            if (caseNode.has("forbidden_risk_levels")) {
                for (JsonNode level : caseNode.get("forbidden_risk_levels")) {
                    if (level.asText().equals(risk)) {
                        accepted = false;
                    }
                }
            }

            String haystack = (assessment.path("summary").asText() + " "
                    + assessment.path("key_factors").toString()).toLowerCase();
            boolean signals = true;
            for (JsonNode keywordGroup : caseNode.get("required_signal_keywords")) {
                boolean anyMatch = false;
                for (String alternative : keywordGroup.asText().split("\\|")) {
                    anyMatch |= haystack.contains(alternative.toLowerCase());
                }
                signals &= anyMatch;
            }
            return new CaseResult(name, accepted, signals, risk, "");
        } catch (Exception e) {
            return new CaseResult(name, false, false, "-", e.getMessage());
        }
    }
}
