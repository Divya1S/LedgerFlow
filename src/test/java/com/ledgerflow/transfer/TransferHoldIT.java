package com.ledgerflow.transfer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.LedgerAssertions;
import com.ledgerflow.support.Money;
import com.ledgerflow.transfer.domain.ScheduledTransferRunner;
import com.ledgerflow.transfer.domain.TransferHoldRunner;
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
 * Held transfers: a first, larger payment to someone new waits on hold,
 * can be cancelled by the sender, and is released exactly once.
 *
 * Alice starts each test with 1,000.00. The threshold is 200.00. Bob's
 * wallet is made to look a month old, so that only the tests about new
 * accounts see the "recipient account is new" signal.
 */
@IntegrationTest
@SuppressWarnings("unchecked")
class TransferHoldIT {

    private static final String SUSPENSE = "00000000-0000-7000-8000-000000000003";

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransferHoldRunner runner;
    @Autowired
    ScheduledTransferRunner scheduledRunner;

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
        money.deposit(alice, aliceWallet, 100_000);
        age(bobWallet);
    }

    // --- helpers ------------------------------------------------------------

    /** Makes an account look established, so it does not trip the new-account signal. */
    private void age(String accountId) {
        jdbc.sql("UPDATE accounts SET created_at = now() - interval '30 days' WHERE id = :id")
                .param("id", UUID.fromString(accountId)).update();
    }

    private void makeDue(String holdId) {
        jdbc.sql("UPDATE transfer_holds SET release_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(holdId)).update();
    }

    private String holdStatus(String holdId) {
        return jdbc.sql("SELECT status FROM transfer_holds WHERE id = :id")
                .param("id", UUID.fromString(holdId)).query(String.class).single();
    }

    /** Sends a transfer and returns the id of the hold it was put on. */
    private String sendHeld(ApiTestClient.Session from, String fromWallet, String toWallet, long amount) {
        ResponseEntity<Map> sent = money.transfer(from, fromWallet, toWallet, amount, "first payment");
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sent.getBody().get("status")).as("transfer of %d should be held", amount).isEqualTo("HELD");
        return (String) ((Map<?, ?>) sent.getBody().get("hold")).get("holdId");
    }

    private ResponseEntity<Map> cancel(ApiTestClient.Session who, String holdId, String key) {
        return api.post(who.token(), "/api/v1/transfer-holds/" + holdId + "/cancel", Map.of(), key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> holds(ApiTestClient.Session who, String side) {
        return (List<Map<String, Object>>) api.get(who.token(), "/api/v1/transfer-holds").getBody().get(side);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history(ApiTestClient.Session who) {
        return (List<Map<String, Object>>) api.get(who.token(), "/api/v1/transactions").getBody().get("items");
    }

    private Map<?, ?> check(ApiTestClient.Session who, String destination, long amount) {
        return api.get(who.token(), "/api/v1/transfers/protection-check?destinationAccountId="
                + destination + "&amountMinorUnits=" + amount).getBody();
    }

    /**
     * Money on hold is never "nowhere": the suspense account holds exactly
     * the total of open holds. Read in one statement so both numbers come
     * from the same snapshot even while a runner is working.
     */
    private void assertSuspenseEqualsOpenHolds() {
        Long difference = jdbc.sql("""
                        SELECT (SELECT balance FROM account_balances WHERE account_id = :suspense)
                             - (SELECT COALESCE(sum(amount), 0) FROM transfer_holds WHERE status = 'HELD')
                        """)
                .param("suspense", UUID.fromString(SUSPENSE)).query(Long.class).single();
        assertThat(difference).as("suspense balance minus open holds").isZero();
    }

    // --- tests --------------------------------------------------------------

    @Test
    void smallAndRepeatPaymentsStayInstant() {
        // Below the threshold: no hold, even to a stranger.
        assertThat(check(alice, bobWallet, 19_999).get("held")).isEqualTo(false);
        ResponseEntity<Map> small = money.transfer(alice, aliceWallet, bobWallet, 19_999, "small");
        assertThat(small.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(small.getBody().get("hold")).isNull();
        assertThat(money.balance(bob, bobWallet)).isEqualTo(19_999);

        // That payment arrived, so Bob is no longer a stranger: a large one is instant too.
        assertThat(check(alice, bobWallet, 50_000).get("held")).isEqualTo(false);
        assertThat(money.transfer(alice, aliceWallet, bobWallet, 50_000, "large, known").getBody().get("status"))
                .isEqualTo("COMPLETED");

        // Trust works in both directions: Dave once paid Carol, so Carol can pay Dave at once.
        ApiTestClient.Session carol = api.registerNamed("Carol Diaz");
        ApiTestClient.Session dave = api.registerNamed("Dave Lund");
        String carolWallet = money.openWallet(carol, "wallet");
        String daveWallet = money.openWallet(dave, "wallet");
        money.deposit(carol, carolWallet, 60_000);
        money.deposit(dave, daveWallet, 1_000);
        money.transfer(dave, daveWallet, carolWallet, 500, "thanks");
        assertThat(money.transfer(carol, carolWallet, daveWallet, 40_000, "back at you").getBody().get("status"))
                .isEqualTo("COMPLETED");

        // Moving money between your own accounts is never held.
        String savings = money.openWallet(alice, "Savings");
        assertThat(money.transfer(alice, aliceWallet, savings, 25_000, "save").getBody().get("status"))
                .isEqualTo("COMPLETED");
        assertSuspenseEqualsOpenHolds();
    }

    @Test
    void firstLargePaymentIsHeldThenDeliveredExactlyOnce() {
        // The sender is told before sending: held, for how long, and why.
        Map<?, ?> preview = check(alice, bobWallet, 40_000);
        assertThat(preview.get("held")).isEqualTo(true);
        assertThat(preview.get("riskLevel")).isEqualTo("ELEVATED");
        assertThat((List<Object>) preview.get("reasons")).containsExactly("FIRST_PAYMENT");
        assertThat(preview.get("holdMinutes")).isEqualTo(60);

        ResponseEntity<Map> sent = money.transfer(alice, aliceWallet, bobWallet, 40_000, "bike");
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sent.getBody().get("status")).isEqualTo("HELD");
        Map<?, ?> hold = (Map<?, ?>) sent.getBody().get("hold");
        String holdId = (String) hold.get("holdId");
        OffsetDateTime releaseAt = OffsetDateTime.parse((String) hold.get("releaseAt"));
        assertThat(releaseAt).isBetween(OffsetDateTime.now().plusMinutes(55), OffsetDateTime.now().plusMinutes(65));

        // The money has left Alice and has not reached Bob. It is in suspense.
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(60_000);
        assertThat(money.balance(bob, bobWallet)).isZero();
        assertSuspenseEqualsOpenHolds();

        // Each side sees the hold, differently. Bob never sees why it was held.
        Map<String, Object> outgoing = holds(alice, "outgoing").getFirst();
        assertThat(outgoing.get("counterparty")).isEqualTo("Bob Reyes");
        assertThat(outgoing.get("canCancel")).isEqualTo(true);
        assertThat((List<Object>) outgoing.get("reasons")).containsExactly("FIRST_PAYMENT");
        Map<String, Object> incoming = holds(bob, "incoming").getFirst();
        assertThat(incoming.get("counterparty")).isEqualTo("Alice Stone");
        assertThat(incoming.get("canCancel")).isEqualTo(false);
        assertThat((List<Object>) incoming.get("reasons")).isEmpty();
        assertThat(incoming.get("riskLevel")).isNull();
        assertThat(cancel(bob, holdId, Money.key()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // History is what touched your own balance: Alice has one line, Bob has none yet.
        assertThat(history(alice)).extracting(t -> t.get("type")).containsExactly("HOLD", "DEPOSIT");
        assertThat(history(alice).getFirst().get("counterparty")).isEqualTo("Bob Reyes");
        assertThat(((Map<?, ?>) history(alice).getFirst().get("hold")).get("status")).isEqualTo("HELD");
        assertThat(history(bob)).isEmpty();

        // A second payment while the first is still on hold is held as well:
        // nothing has arrived yet, so there is no relationship to trust.
        String secondHold = sendHeld(alice, aliceWallet, bobWallet, 25_000);

        // Not due yet: polling changes nothing.
        runner.releaseDue();
        assertThat(holdStatus(holdId)).isEqualTo("HELD");

        // The release time passes. The runner delivers it, once.
        makeDue(holdId);
        runner.releaseDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(holdStatus(holdId)).isEqualTo("RELEASED"));
        runner.releaseDue();
        assertThat(money.balance(bob, bobWallet)).isEqualTo(40_000);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(35_000);
        assertThat(holdStatus(secondHold)).isEqualTo("HELD");
        assertSuspenseEqualsOpenHolds();

        // Bob now has one ordinary-looking incoming transfer, named after Alice.
        assertThat(history(bob)).hasSize(1);
        assertThat(history(bob).getFirst().get("type")).isEqualTo("HOLD_RELEASE");
        assertThat(history(bob).getFirst().get("direction")).isEqualTo("IN");
        assertThat(history(bob).getFirst().get("counterparty")).isEqualTo("Alice Stone");
        // Alice still has one line per transfer she sent, never a duplicate for the release.
        assertThat(history(alice)).extracting(t -> t.get("type")).containsExactly("HOLD", "HOLD", "DEPOSIT");
        assertThat(api.get(alice.token(), "/api/v1/transactions?type=TRANSFER").getBody().get("items"))
                .asList().hasSize(2);

        // Delivered means final: it can no longer be cancelled.
        ResponseEntity<Map> tooLate = cancel(alice, holdId, Money.key());
        assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooLate.getBody().get("code")).isEqualTo("HOLD_ALREADY_SETTLED");

        // And because a payment has now arrived, the next large one is instant.
        assertThat(check(alice, bobWallet, 30_000).get("held")).isEqualTo(false);

        // The overview counts a delivered or pending hold as money out, under transfers.
        Map<?, ?> overview = api.get(alice.token(), "/api/v1/insights/overview?days=7").getBody();
        assertThat(((Number) ((Map<?, ?>) overview.get("current")).get("moneyOutMinorUnits")).longValue())
                .isEqualTo(65_000);
        assertThat(((Map<?, ?>) ((List<Object>) overview.get("spending")).getFirst()).get("category"))
                .isEqualTo("TRANSFERS");

        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertPerTransactionBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(UUID.fromString(aliceWallet), UUID.fromString(bobWallet), UUID.fromString(SUSPENSE)));

        // Both people were kept informed along the way.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(notificationTypes(bob.userId())).contains("TRANSFER_ON_ITS_WAY", "MONEY_RECEIVED");
            assertThat(notificationTypes(alice.userId())).contains("TRANSFER_DELIVERED");
        });
    }

    @Test
    void senderCanCancelAndGetsEverythingBack() {
        String holdId = sendHeld(alice, aliceWallet, bobWallet, 30_000);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(70_000);

        // Cancelling moves money, so it needs an idempotency key.
        assertThat(cancel(alice, holdId, null).getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        String key = Money.key();
        ResponseEntity<Map> cancelled = cancel(alice, holdId, key);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(100_000);
        assertThat(money.balance(bob, bobWallet)).isZero();

        // A retry of the same click replays; a new attempt is told it is already done.
        assertThat(cancel(alice, holdId, key).getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(cancel(alice, holdId, Money.key()).getBody().get("code")).isEqualTo("HOLD_ALREADY_SETTLED");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(100_000);

        // Even past its release time, a cancelled hold is never delivered.
        makeDue(holdId);
        runner.releaseDue();
        assertThat(money.balance(bob, bobWallet)).isZero();
        assertThat(holds(bob, "incoming")).isEmpty();

        // Alice's history tells the whole story: sent, then returned.
        List<Map<String, Object>> lines = history(alice);
        assertThat(lines).extracting(t -> t.get("type")).containsExactly("HOLD_RETURN", "HOLD", "DEPOSIT");
        assertThat(lines.get(0).get("direction")).isEqualTo("IN");
        assertThat(lines.get(0).get("counterparty")).isEqualTo("Held funds");
        assertThat(((Map<?, ?>) lines.get(1).get("hold")).get("status")).isEqualTo("CANCELLED");

        // The entries behind the hold show exactly where the money waited.
        String holdTransaction = (String) lines.get(1).get("id");
        Map<?, ?> entries = api.get(alice.token(), "/api/v1/transactions/" + holdTransaction + "/entries").getBody();
        assertThat(((Number) entries.get("sumMinorUnits")).longValue()).isZero();
        List<Map<String, Object>> legs = (List<Map<String, Object>>) entries.get("entries");
        assertThat(legs).extracting(leg -> leg.get("account")).containsExactly("Everyday", "Held funds");

        // Money that left and came back was never spending or income...
        Map<?, ?> overview = api.get(alice.token(), "/api/v1/insights/overview?days=7").getBody();
        Map<?, ?> current = (Map<?, ?>) overview.get("current");
        assertThat(((Number) current.get("moneyOutMinorUnits")).longValue()).isZero();
        assertThat(((Number) current.get("moneyInMinorUnits")).longValue()).isEqualTo(100_000);
        assertThat((List<Object>) overview.get("spending")).isEmpty();
        // ...and the balance history still ends where the real balance is.
        assertThat(((Number) overview.get("totalBalanceMinorUnits")).longValue()).isEqualTo(100_000);
        assertThat(((Number) overview.get("balanceChangeMinorUnits")).longValue()).isEqualTo(100_000);

        // Nothing arrived, so Bob is still a stranger: the next large payment is held again.
        assertThat(check(alice, bobWallet, 30_000).get("held")).isEqualTo(true);

        assertSuspenseEqualsOpenHolds();
        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationTypes(bob.userId())).contains("TRANSFER_ON_ITS_WAY", "TRANSFER_CANCELLED"));
    }

    @Test
    void cancelAndReleaseRacingSettleTheHoldExactlyOnce() throws Exception {
        String holdId = sendHeld(alice, aliceWallet, bobWallet, 20_000);

        int each = 4;
        ExecutorService pool = Executors.newFixedThreadPool(each * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < each; i++) {
            results.add(pool.submit(waitThen(start, () -> runner.releaseDue())));
            results.add(pool.submit(waitThen(start, () -> cancel(alice, holdId, Money.key()).getStatusCode())));
        }
        makeDue(holdId);
        start.countDown();
        for (Future<Object> result : results) {
            result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(holdStatus(holdId)).isIn("RELEASED", "CANCELLED"));
        // Exactly one settling transaction exists: delivered or returned, never both, never twice.
        Long settlements = jdbc.sql("""
                        SELECT count(*) FROM transactions
                        WHERE (type = 'HOLD_RELEASE' AND source_account_id = :alice)
                           OR (type = 'HOLD_RETURN' AND destination_account_id = :alice)
                        """)
                .param("alice", UUID.fromString(aliceWallet)).query(Long.class).single();
        assertThat(settlements).isEqualTo(1);

        boolean released = "RELEASED".equals(holdStatus(holdId));
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(released ? 80_000 : 100_000);
        assertThat(money.balance(bob, bobWallet)).isEqualTo(released ? 20_000 : 0);
        assertSuspenseEqualsOpenHolds();
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(UUID.fromString(aliceWallet), UUID.fromString(bobWallet), UUID.fromString(SUSPENSE)));
    }

    @Test
    void evidenceAboutTheRecipientMeansALongerHold() {
        // A wallet opened minutes ago is a classic sign of an account made to receive stolen money.
        ApiTestClient.Session erin = api.registerNamed("Erin Park");
        String erinWallet = money.openWallet(erin, "wallet");
        Map<?, ?> newAccount = check(alice, erinWallet, 40_000);
        assertThat(newAccount.get("riskLevel")).isEqualTo("HIGH");
        assertThat((List<Object>) newAccount.get("reasons")).containsExactly("FIRST_PAYMENT", "RECIPIENT_ACCOUNT_NEW");
        assertThat(newAccount.get("holdMinutes")).isEqualTo(1440);

        // So is an established account that several strangers suddenly pay on the same day.
        assertThat(check(alice, bobWallet, 40_000).get("riskLevel")).isEqualTo("ELEVATED");
        for (String name : List.of("Stranger One", "Stranger Two")) {
            ApiTestClient.Session stranger = api.registerNamed(name);
            String wallet = money.openWallet(stranger, "wallet");
            money.deposit(stranger, wallet, 50_000);
            sendHeld(stranger, wallet, bobWallet, 30_000);
        }
        Map<?, ?> paidByStrangers = check(alice, bobWallet, 40_000);
        assertThat(paidByStrangers.get("riskLevel")).isEqualTo("HIGH");
        assertThat((List<Object>) paidByStrangers.get("reasons"))
                .containsExactly("FIRST_PAYMENT", "RECIPIENT_PAID_BY_STRANGERS");

        // The transfer itself uses the same rule as the preview.
        ResponseEntity<Map> sent = money.transfer(alice, aliceWallet, bobWallet, 40_000, "deposit for flat");
        Map<?, ?> hold = (Map<?, ?>) sent.getBody().get("hold");
        assertThat(hold.get("riskLevel")).isEqualTo("HIGH");
        assertThat(OffsetDateTime.parse((String) hold.get("releaseAt")))
                .isAfter(OffsetDateTime.now().plusHours(23));
        assertSuspenseEqualsOpenHolds();
    }

    @Test
    void anAccountWithMoneyOnHoldCannotBeClosedUntilItSettles() {
        // Alice empties a second wallet into a held transfer, then tries to close it.
        String spare = money.openWallet(alice, "Spare");
        money.transfer(alice, aliceWallet, spare, 25_000, "fund spare");
        String holdId = sendHeld(alice, spare, bobWallet, 25_000);
        assertThat(money.balance(alice, spare)).isZero();

        ResponseEntity<Map> refused = api.delete(alice.token(), "/api/v1/accounts/" + spare);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("ACCOUNT_HAS_HELD_TRANSFERS");
        // The same holds for the wallet the money is going to.
        assertThat(api.delete(bob.token(), "/api/v1/accounts/" + bobWallet).getBody().get("code"))
                .isEqualTo("ACCOUNT_HAS_HELD_TRANSFERS");

        makeDue(holdId);
        runner.releaseDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(holdStatus(holdId)).isEqualTo("RELEASED"));
        assertThat(api.delete(alice.token(), "/api/v1/accounts/" + spare).getBody().get("status"))
                .isEqualTo("CLOSED");
    }

    @Test
    void aHoldThatCannotBeDeliveredGoesBackToTheSender() {
        String holdId = sendHeld(alice, aliceWallet, bobWallet, 30_000);
        // Force the one case the close guard normally prevents: the recipient's wallet is gone.
        jdbc.sql("UPDATE accounts SET status = 'CLOSED' WHERE id = :id")
                .param("id", UUID.fromString(bobWallet)).update();

        makeDue(holdId);
        runner.releaseDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(holdStatus(holdId)).isEqualTo("RETURNED"));

        // The money is not stranded in suspense: Alice has it back.
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(100_000);
        assertSuspenseEqualsOpenHolds();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationTypes(alice.userId())).contains("TRANSFER_RETURNED"));
    }

    @Test
    void scheduledRunsAndMoneyRequestsGetTheSameProtection() {
        // Paying a stranger's money request is how "pay this link" scams work.
        String requestId = (String) api.post(bob.token(), "/api/v1/payment-requests", Map.of(
                "destinationAccountId", bobWallet, "amountMinorUnits", 30_000, "currency", "USD",
                "note", "deposit")).getBody().get("id");
        ResponseEntity<Map> paid = api.post(alice.token(), "/api/v1/payment-requests/" + requestId + "/pay",
                Map.of("sourceAccountId", aliceWallet), Money.key());
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(paid.getBody().get("status")).isEqualTo("PAID");
        assertThat(paid.getBody().get("heldUntil")).isNotNull();
        assertThat(money.balance(bob, bobWallet)).isZero();

        // Alice thinks again and cancels. The request is unpaid once more, not stuck as "paid".
        String holdId = (String) holds(alice, "outgoing").getFirst().get("id");
        assertThat(cancel(alice, holdId, Money.key()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get(bob.token(), "/api/v1/payment-requests/" + requestId).getBody().get("status"))
                .isEqualTo("PENDING");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(100_000);

        // A scheduled transfer to someone new is held when it runs, and the run is recorded as made.
        String scheduleId = (String) api.post(alice.token(), "/api/v1/scheduled-transfers", Map.of(
                "sourceAccountId", aliceWallet, "destinationAccountId", bobWallet,
                "amountMinorUnits", 25_000, "currency", "USD", "frequency", "ONCE",
                "startDate", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString())).getBody().get("id");
        scheduledRunner.runDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT last_outcome FROM scheduled_transfers WHERE id = :id")
                        .param("id", UUID.fromString(scheduleId)).query(String.class).single())
                        .isEqualTo("HELD"));
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(75_000);
        assertThat(money.balance(bob, bobWallet)).isZero();
        assertThat(holds(bob, "incoming")).hasSize(1);
        assertSuspenseEqualsOpenHolds();
    }

    private static Callable<Object> waitThen(CountDownLatch start, Callable<Object> action) {
        return () -> {
            start.await();
            return action.call();
        };
    }

    private List<String> notificationTypes(UUID userId) {
        return jdbc.sql("SELECT type FROM notifications WHERE user_id = :u").param("u", userId)
                .query(String.class).list();
    }
}
