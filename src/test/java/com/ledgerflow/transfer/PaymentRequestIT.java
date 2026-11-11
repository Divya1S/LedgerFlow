package com.ledgerflow.transfer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.LedgerAssertions;
import com.ledgerflow.support.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money requests: created by one person, paid by another, exactly once.
 */
@IntegrationTest
class PaymentRequestIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;
    Money money;

    ApiTestClient.Session alice;
    ApiTestClient.Session bob;
    String aliceWallet;
    String bobWallet;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
        money = new Money(api);
        alice = api.registerNamed("Alice Stone");
        bob = api.registerNamed("Bob Reyes");
        aliceWallet = money.openWallet(alice, "Everyday");
        bobWallet = money.openWallet(bob, "Bob wallet");
        money.deposit(bob, bobWallet, 10_000);
    }

    private String request(long amount, String note) {
        ResponseEntity<Map> created = api.post(alice.token(), "/api/v1/payment-requests", Map.of(
                "destinationAccountId", aliceWallet, "amountMinorUnits", amount, "currency", "USD", "note", note));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("PENDING");
        assertThat(created.getBody().get("mine")).isEqualTo(true);
        return (String) created.getBody().get("id");
    }

    private ResponseEntity<Map> pay(ApiTestClient.Session payer, String requestId, String fromAccount, String key) {
        return api.post(payer.token(), "/api/v1/payment-requests/" + requestId + "/pay",
                Map.of("sourceAccountId", fromAccount), key);
    }

    @Test
    void requestIsPaidOnceAndOnlyOnce() {
        String id = request(2_500, "dinner");

        // The payer sees who is asking and how much, but not where the money will land.
        @SuppressWarnings("unchecked")
        Map<String, Object> seenByBob = api.get(bob.token(), "/api/v1/payment-requests/" + id).getBody();
        assertThat(seenByBob.get("requesterName")).isEqualTo("Alice Stone");
        assertThat(seenByBob.get("mine")).isEqualTo(false);
        assertThat(seenByBob.get("note")).isEqualTo("dinner");
        assertThat(seenByBob).doesNotContainKeys("destinationAccountId", "requesterUserId");

        String key = Money.key();
        ResponseEntity<Map> paid = pay(bob, id, bobWallet, key);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(paid.getBody().get("status")).isEqualTo("PAID");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(2_500);
        assertThat(money.balance(bob, bobWallet)).isEqualTo(7_500);

        // A retry of the same click replays; a second, different click is refused.
        assertThat(pay(bob, id, bobWallet, key).getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        ResponseEntity<Map> again = pay(bob, id, bobWallet, Money.key());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("REQUEST_ALREADY_PAID");
        assertThat(money.balance(bob, bobWallet)).isEqualTo(7_500);

        // The requester sees who paid; a paid request cannot be cancelled.
        Map<?, ?> mine = (Map<?, ?>) api.getList(alice.token(), "/api/v1/payment-requests").getBody().getFirst();
        assertThat(mine.get("payerName")).isEqualTo("Bob Reyes");
        assertThat(api.post(alice.token(), "/api/v1/payment-requests/" + id + "/cancel", Map.of())
                .getBody().get("code")).isEqualTo("REQUEST_ALREADY_PAID");

        // It arrived as a normal transfer, described by the request's note.
        Map<?, ?> latest = (Map<?, ?>) ((List<?>) api.get(alice.token(), "/api/v1/transactions?limit=1")
                .getBody().get("items")).getFirst();
        assertThat(latest.get("description")).isEqualTo("Money request: dinner");
        assertThat(latest.get("counterparty")).isEqualTo("Bob Reyes");

        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(UUID.fromString(aliceWallet), UUID.fromString(bobWallet)));
    }

    @Test
    void twoPeoplePayingTheSameLinkAtOnceOnlyOneSucceeds() throws Exception {
        String id = request(3_000, "concert ticket");
        ApiTestClient.Session carol = api.registerNamed("Carol Diaz");
        String carolWallet = money.openWallet(carol, "wallet");
        money.deposit(carol, carolWallet, 10_000);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ResponseEntity<Map>> first = pool.submit(() -> {
            start.await();
            return pay(bob, id, bobWallet, Money.key());
        });
        Future<ResponseEntity<Map>> second = pool.submit(() -> {
            start.await();
            return pay(carol, id, carolWallet, Money.key());
        });
        start.countDown();
        List<HttpStatus> statuses = List.of(
                HttpStatus.valueOf(first.get(30, TimeUnit.SECONDS).getStatusCode().value()),
                HttpStatus.valueOf(second.get(30, TimeUnit.SECONDS).getStatusCode().value()));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        // Alice received the amount once; exactly one of the two paid it.
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(3_000);
        assertThat(money.balance(bob, bobWallet) + money.balance(carol, carolWallet)).isEqualTo(17_000);
        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
    }

    @Test
    void cancelledAndExpiredRequestsCannotBePaid() {
        String cancelled = request(1_000, "never mind");
        // Only the requester can cancel.
        assertThat(api.post(bob.token(), "/api/v1/payment-requests/" + cancelled + "/cancel", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post(alice.token(), "/api/v1/payment-requests/" + cancelled + "/cancel", Map.of())
                .getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(pay(bob, cancelled, bobWallet, Money.key()).getBody().get("code")).isEqualTo("REQUEST_CANCELLED");

        String expired = request(1_000, "too late");
        jdbc.sql("UPDATE payment_requests SET expires_at = now() - interval '1 day' WHERE id = :id")
                .param("id", UUID.fromString(expired)).update();
        assertThat(api.get(bob.token(), "/api/v1/payment-requests/" + expired).getBody().get("status"))
                .isEqualTo("EXPIRED");
        assertThat(pay(bob, expired, bobWallet, Money.key()).getBody().get("code")).isEqualTo("REQUEST_EXPIRED");

        assertThat(money.balance(bob, bobWallet)).isEqualTo(10_000);
    }

    @Test
    void failedPaymentLeavesTheRequestPayable() {
        String id = request(50_000, "more than Bob has");

        ResponseEntity<Map> refused = pay(bob, id, bobWallet, Money.key());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(api.get(alice.token(), "/api/v1/payment-requests/" + id).getBody().get("status"))
                .isEqualTo("PENDING");

        // Paying from an account that is not yours fails the same way as an unknown account.
        assertThat(pay(bob, id, aliceWallet, Money.key()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // You can only ask for money into your own account.
        assertThat(api.post(bob.token(), "/api/v1/payment-requests", Map.of(
                "destinationAccountId", aliceWallet, "amountMinorUnits", 100, "currency", "USD"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get(bob.token(), "/api/v1/payment-requests/" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
