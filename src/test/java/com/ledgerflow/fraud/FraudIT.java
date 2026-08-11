package com.ledgerflow.fraud;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The fraud service consumes payment events asynchronously and writes
 * verdicts. It never touches money: the payment it flags is already
 * committed and stays committed.
 */
@IntegrationTest
class FraudIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    private record Fixture(ApiTestClient.Session payer, String wallet, String shop) {
    }

    private Fixture fixture(long funding) {
        ApiTestClient.Session payer = api.registerAndLogin();
        ApiTestClient.Session merchant = api.registerAndLogin();
        String wallet = (String) api.post(payer.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "w")).getBody().get("id");
        String shop = (String) api.post(merchant.token(), "/api/v1/accounts",
                Map.of("type", "MERCHANT", "currency", "USD", "name", "s")).getBody().get("id");
        api.post(payer.token(), "/api/v1/accounts/" + wallet + "/deposits",
                Map.of("amountMinorUnits", funding, "currency", "USD"), UUID.randomUUID().toString());
        return new Fixture(payer, wallet, shop);
    }

    private String pay(Fixture f, long amount) {
        return (String) api.post(f.payer().token(), "/api/v1/payments",
                Map.of("sourceAccountId", f.wallet(), "destinationAccountId", f.shop(),
                        "amountMinorUnits", amount, "currency", "USD"),
                UUID.randomUUID().toString()).getBody().get("paymentId");
    }

    private Object[] decision(String paymentId) {
        return jdbc.sql("""
                        SELECT verdict, rule_hits::text FROM fraud_decisions WHERE payment_id = :id
                        """)
                .param("id", UUID.fromString(paymentId))
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getString(2)})
                .optional().orElse(null);
    }

    @Test
    void ordinaryPaymentIsApproved() {
        Fixture f = fixture(100_000);
        String paymentId = pay(f, 5_000); // $50

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Object[] d = decision(paymentId);
            assertThat(d).isNotNull();
            assertThat(d[0]).isEqualTo("APPROVED");
        });
    }

    @Test
    void largePaymentIsFlaggedForReviewWithoutTouchingTheMoney() {
        Fixture f = fixture(1_000_000);
        String paymentId = pay(f, 150_000); // $1,500 >= large threshold

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Object[] d = decision(paymentId);
            assertThat(d).isNotNull();
            assertThat(d[0]).isEqualTo("REVIEW");
            assertThat((String) d[1]).contains("LARGE_AMOUNT");
        });

        // The verdict did not (and cannot) alter the committed payment.
        String status = jdbc.sql("SELECT status FROM payments WHERE id = :id")
                .param("id", UUID.fromString(paymentId)).query(String.class).single();
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void veryLargePaymentIsRejectedVerdict() {
        Fixture f = fixture(1_000_000);
        String paymentId = pay(f, 600_000); // $6,000 >= very large threshold

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Object[] d = decision(paymentId);
            assertThat(d).isNotNull();
            assertThat(d[0]).isEqualTo("REJECTED");
            assertThat((String) d[1]).contains("VERY_LARGE_AMOUNT");
        });
    }
}
