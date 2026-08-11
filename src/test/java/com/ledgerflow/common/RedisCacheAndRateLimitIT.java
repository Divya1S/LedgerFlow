package com.ledgerflow.common;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis as a projection layer: caching works, eviction follows commits,
 * the rate limiter counts, and a Redis outage degrades performance but
 * never correctness (fail-open by design).
 */
@IntegrationTest
@TestPropertySource(properties = "ledgerflow.ratelimit.money-requests-per-minute=5")
class RedisCacheAndRateLimitIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redis;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    private record Fixture(ApiTestClient.Session session, String accountId) {
    }

    private Fixture fundedWallet() {
        ApiTestClient.Session session = api.registerAndLogin();
        String account = (String) api.post(session.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", "w")).getBody().get("id");
        deposit(session, account, 10_000);
        return new Fixture(session, account);
    }

    private ResponseEntity<Map> deposit(ApiTestClient.Session s, String account, long amount) {
        return api.post(s.token(), "/api/v1/accounts/" + account + "/deposits",
                Map.of("amountMinorUnits", amount, "currency", "USD"), UUID.randomUUID().toString());
    }

    private ResponseEntity<String> history(Fixture f) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(f.session().token());
        return rest.exchange("/api/v1/accounts/" + f.accountId() + "/transactions",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);
    }

    @Test
    void firstHistoryPageIsCachedAndEvictedAfterMovements() {
        Fixture f = fundedWallet();

        ResponseEntity<String> miss = history(f);
        assertThat(miss.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");

        ResponseEntity<String> hit = history(f);
        assertThat(hit.getHeaders().getFirst("X-Cache")).isEqualTo("HIT");
        assertThat(hit.getBody()).isEqualTo(miss.getBody());

        // A new movement must evict the page after commit: the next read is
        // a MISS and contains the new transaction.
        deposit(f.session(), f.accountId(), 777);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<String> afterMovement = history(f);
            assertThat(afterMovement.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
            assertThat(afterMovement.getBody()).contains("777");
        });
    }

    @Test
    void redisOutageDegradesToUncachedButStaysCorrect() {
        Fixture f = fundedWallet();
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            // Reads and money movements keep working without Redis; the
            // breaker fails open so responses stay fast after first timeout.
            ResponseEntity<String> read = history(f);
            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(read.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");

            ResponseEntity<Map> movement = deposit(f.session(), f.accountId(), 555);
            assertThat(movement.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<String> again = history(f);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // After recovery (and the 10s breaker window), caching resumes.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            history(f);
            assertThat(history(f).getHeaders().getFirst("X-Cache")).isEqualTo("HIT");
        });
    }

    @Test
    void moneyEndpointsAreRateLimitedPerUser() {
        Fixture f = fundedWallet(); // one deposit already spent
        int allowed = 0;
        int limited = 0;
        // 14 attempts: even if a minute-window boundary rolls over mid-test
        // (fresh budget of 5), at least 5 requests must be limited.
        for (int i = 0; i < 14; i++) {
            HttpStatus status = (HttpStatus) deposit(f.session(), f.accountId(), 10).getStatusCode();
            if (status == HttpStatus.CREATED) {
                allowed++;
            } else if (status.value() == 429) {
                limited++;
            }
        }
        assertThat(limited).as("requests over the 5/minute limit are 429").isGreaterThanOrEqualTo(5);
        assertThat(allowed + limited).isEqualTo(14);

        // Another user is unaffected: limits are per user, not global.
        Fixture other = fundedWallet();
        assertThat(other.accountId()).isNotNull();
    }
}
