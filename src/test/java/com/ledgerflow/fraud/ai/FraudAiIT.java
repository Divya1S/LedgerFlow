package com.ledgerflow.fraud.ai;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The AI copilot pipeline with a deterministic mock model (provided for
 * every test context by TestcontainersConfiguration): flagged payment,
 * verdict commits, assessment stored, served with merchant-only
 * authorization. Live-model quality is measured separately by
 * FraudAnalystEvalTest (llm-eval tag).
 */
@IntegrationTest
class FraudAiIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FraudAnalystService analystService;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    private record Fixture(ApiTestClient.Session payer, ApiTestClient.Session merchant, String paymentId) {
    }

    private Fixture flaggedPayment(long amount) {
        ApiTestClient.Session payer = api.registerAndLogin();
        ApiTestClient.Session merchant = api.registerAndLogin();
        String wallet = (String) api.post(payer.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "w")).getBody().get("id");
        String shop = (String) api.post(merchant.token(), "/api/v1/accounts",
                Map.of("type", "MERCHANT", "currency", "USD", "name", "s")).getBody().get("id");
        api.post(payer.token(), "/api/v1/accounts/" + wallet + "/deposits",
                Map.of("amountMinorUnits", 1_000_000, "currency", "USD"), UUID.randomUUID().toString());
        String paymentId = (String) api.post(payer.token(), "/api/v1/payments",
                Map.of("sourceAccountId", wallet, "destinationAccountId", shop,
                        "amountMinorUnits", amount, "currency", "USD",
                        "description", "large order"), UUID.randomUUID().toString())
                .getBody().get("paymentId");
        return new Fixture(payer, merchant, paymentId);
    }

    @Test
    void flaggedPaymentGetsStoredAssessmentServedToMerchantOnly() {
        Fixture f = flaggedPayment(150_000); // REVIEW territory

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String risk = jdbc.sql("""
                            SELECT ai_assessment->>'risk_level' FROM fraud_decisions
                            WHERE payment_id = :id AND ai_assessment IS NOT NULL
                            """)
                    .param("id", UUID.fromString(f.paymentId()))
                    .query(String.class).optional().orElse(null);
            assertThat(risk).isEqualTo("MEDIUM"); // mock maps REVIEW to MEDIUM
        });

        ResponseEntity<Map> forMerchant = api.get(f.merchant().token(),
                "/api/v1/payments/" + f.paymentId() + "/fraud-assessment");
        assertThat(forMerchant.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forMerchant.getBody().get("verdict")).isEqualTo("REVIEW");
        assertThat(((Map<?, ?>) forMerchant.getBody().get("aiAssessment")).get("risk_level"))
                .isEqualTo("MEDIUM");
        assertThat(forMerchant.getBody().get("aiModel")).isEqualTo("mock-analyst-1");

        // Fraud reasoning is not shown to the payer.
        assertThat(api.get(f.payer().token(), "/api/v1/payments/" + f.paymentId() + "/fraud-assessment")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void toolLoopExecutesRealReadOnlyToolsAgainstRealData() {
        Fixture f = flaggedPayment(150_000);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT count(*) FROM fraud_decisions WHERE payment_id = :id")
                        .param("id", UUID.fromString(f.paymentId())).query(Long.class).single())
                .isEqualTo(1L));

        // Drive the assessment synchronously with a local mock instance so
        // the tool interactions are observable and deterministic.
        MockLlmClient localMock = new MockLlmClient();
        analystService.assess(localMock, UUID.fromString(f.paymentId()),
                "REVIEW", 60, "[\"LARGE_AMOUNT\"]");

        assertThat(localMock.toolCallsSeen).contains(
                "get_payment_details", "get_payer_recent_activity", "get_account_standing");
        String model = jdbc.sql("SELECT ai_model FROM fraud_decisions WHERE payment_id = :id")
                .param("id", UUID.fromString(f.paymentId())).query(String.class).single();
        assertThat(model).isEqualTo("mock-analyst-1");
    }

    @Test
    void approvedPaymentsGetNoAssessment() {
        Fixture f = flaggedPayment(2_000); // well under every threshold

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String verdict = jdbc.sql("SELECT verdict FROM fraud_decisions WHERE payment_id = :id")
                    .param("id", UUID.fromString(f.paymentId()))
                    .query(String.class).optional().orElse(null);
            assertThat(verdict).isEqualTo("APPROVED");
        });

        String assessment = jdbc.sql(
                        "SELECT ai_assessment::text FROM fraud_decisions WHERE payment_id = :id")
                .param("id", UUID.fromString(f.paymentId()))
                .query(String.class).optional().orElse(null);
        assertThat(assessment).isNull();
    }
}
