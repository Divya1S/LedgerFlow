package com.ledgerflow.transfer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * Idempotency behavior over the real HTTP API: retries replay, races
 * produce exactly one transaction, reused keys with different bodies fail.
 */
@IntegrationTest
class IdempotencyIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    private record Fixture(ApiTestClient.Session session, String sourceId, String destinationId) {
    }

    private Fixture fundedFixture() {
        ApiTestClient.Session alice = api.registerAndLogin();
        String source = (String) api.post(alice.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "src")).getBody().get("id");
        String destination = (String) api.post(alice.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "dst")).getBody().get("id");
        ResponseEntity<Map> deposit = api.post(alice.token(),
                "/api/v1/accounts/" + source + "/deposits",
                Map.of("amountMinorUnits", 10_000, "currency", "USD"), UUID.randomUUID().toString());
        assertThat(deposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Fixture(alice, source, destination);
    }

    private long transactionCount(String description) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE description = :d")
                .param("d", description).query(Long.class).single();
    }

    @Test
    void retryWithSameKeyReplaysTheStoredResponse() {
        Fixture f = fundedFixture();
        String key = UUID.randomUUID().toString();
        String marker = "replay-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("sourceAccountId", f.sourceId(),
                "destinationAccountId", f.destinationId(),
                "amountMinorUnits", 500, "currency", "USD", "description", marker);

        ResponseEntity<Map> first = api.post(f.session().token(), "/api/v1/transfers", body, key);
        ResponseEntity<Map> second = api.post(f.session().token(), "/api/v1/transfers", body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("transactionId")).isEqualTo(first.getBody().get("transactionId"));
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(transactionCount(marker)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesProduceExactlyOneTransaction() throws Exception {
        Fixture f = fundedFixture();
        String key = UUID.randomUUID().toString();
        String marker = "race-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("sourceAccountId", f.sourceId(),
                "destinationAccountId", f.destinationId(),
                "amountMinorUnits", 500, "currency", "USD", "description", marker);

        AtomicReference<ResponseEntity<Map>> r1 = new AtomicReference<>();
        AtomicReference<ResponseEntity<Map>> r2 = new AtomicReference<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            pool.submit(() -> {
                try {
                    start.await();
                    r1.set(api.post(f.session().token(), "/api/v1/transfers", body, key));
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    r2.set(api.post(f.session().token(), "/api/v1/transfers", body, key));
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(r1.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r1.get().getBody().get("transactionId"))
                .isEqualTo(r2.get().getBody().get("transactionId"));
        assertThat(transactionCount(marker)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        Fixture f = fundedFixture();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("sourceAccountId", f.sourceId(),
                "destinationAccountId", f.destinationId(),
                "amountMinorUnits", 500, "currency", "USD");

        assertThat(api.post(f.session().token(), "/api/v1/transfers", body, key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        Map<String, Object> tampered = Map.of("sourceAccountId", f.sourceId(),
                "destinationAccountId", f.destinationId(),
                "amountMinorUnits", 9_999, "currency", "USD");
        ResponseEntity<Map> response = api.post(f.session().token(), "/api/v1/transfers", tampered, key);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        Fixture f = fundedFixture();
        ResponseEntity<Map> response = api.post(f.session().token(), "/api/v1/transfers",
                Map.of("sourceAccountId", f.sourceId(), "destinationAccountId", f.destinationId(),
                        "amountMinorUnits", 500, "currency", "USD"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void failedRequestsAreNotRecordedSoRetriesCanSucceed() {
        Fixture f = fundedFixture();
        String key = UUID.randomUUID().toString();

        // First attempt fails: more money than the account holds.
        Map<String, Object> tooMuch = Map.of("sourceAccountId", f.sourceId(),
                "destinationAccountId", f.destinationId(),
                "amountMinorUnits", 999_999, "currency", "USD");
        ResponseEntity<Map> failed = api.post(f.session().token(), "/api/v1/transfers", tooMuch, key);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failed.getBody().get("code")).isEqualTo("INSUFFICIENT_FUNDS");

        // The claim rolled back with the transaction, so the same key with a
        // corrected body executes fresh.
        Map<String, Object> affordable = Map.of("sourceAccountId", f.sourceId(),
                "destinationAccountId", f.destinationId(),
                "amountMinorUnits", 100, "currency", "USD");
        ResponseEntity<Map> retried = api.post(f.session().token(), "/api/v1/transfers", affordable, key);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
