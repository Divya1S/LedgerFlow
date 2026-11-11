package com.ledgerflow.transfer;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.ledgerflow.transfer.domain.ScheduledTransferRunner;
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
 * Scheduled transfers: each occurrence runs exactly once, a refused
 * occurrence is recorded instead of retried forever, and two runners
 * polling at the same moment never both run the same schedule.
 *
 * The tests call the runner directly instead of waiting for its timer.
 * The timer may also fire while a test runs; that is the same situation
 * as two app instances and must not change any outcome here.
 */
@IntegrationTest
class ScheduledTransferIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ScheduledTransferRunner runner;

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
        money.deposit(alice, aliceWallet, 10_000);
    }

    private ResponseEntity<Map> schedule(long amount, String frequency, LocalDate startDate) {
        return api.post(alice.token(), "/api/v1/scheduled-transfers", Map.of(
                "sourceAccountId", aliceWallet, "destinationAccountId", bobWallet,
                "amountMinorUnits", amount, "currency", "USD", "description", "rent share",
                "frequency", frequency, "startDate", startDate.toString()));
    }

    private Map<String, Object> row(String scheduleId) {
        return jdbc.sql("SELECT * FROM scheduled_transfers WHERE id = :id")
                .param("id", UUID.fromString(scheduleId)).query().singleRow();
    }

    private void makeDue(String scheduleId) {
        jdbc.sql("UPDATE scheduled_transfers SET next_run_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(scheduleId)).update();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    @Test
    void oneOffScheduleForTodayRunsOnceAndCompletes() {
        ResponseEntity<Map> created = schedule(2_500, "ONCE", today());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(created.getBody().get("destinationLabel")).isEqualTo("Bob Reyes");

        runner.runDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(row(id).get("status")).isEqualTo("COMPLETED"));

        assertThat(row(id).get("last_outcome")).isEqualTo("COMPLETED");
        assertThat(row(id).get("runs_completed")).isEqualTo(1);
        assertThat(row(id).get("next_run_at")).isNull();
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(7_500);
        assertThat(money.balance(bob, bobWallet)).isEqualTo(2_500);

        // Nothing is left to run: polling again moves no more money.
        runner.runDue();
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(7_500);

        // The run is an ordinary transfer in the history, with the schedule's description.
        Map<?, ?> latest = (Map<?, ?>) ((List<?>) api.get(alice.token(), "/api/v1/transactions?limit=1")
                .getBody().get("items")).getFirst();
        assertThat(latest.get("type")).isEqualTo("TRANSFER");
        assertThat(latest.get("description")).isEqualTo("rent share");
    }

    @Test
    void refusedRunIsRecordedAndTheOwnerIsTold() {
        // 100.00 in the wallet, 500.00 scheduled.
        String id = (String) schedule(50_000, "ONCE", today()).getBody().get("id");

        runner.runDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(row(id).get("status")).isEqualTo("FAILED"));

        // The refusal was written to the schedule even though the transfer
        // itself rolled back: that is the savepoint doing its job.
        assertThat(row(id).get("last_outcome")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(row(id).get("runs_completed")).isEqualTo(0);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(10_000);
        assertThat(money.balance(bob, bobWallet)).isZero();
        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String body = jdbc.sql("""
                            SELECT body FROM notifications
                            WHERE user_id = :u AND type = 'SCHEDULED_TRANSFER_FAILED'
                            """)
                    .param("u", alice.userId()).query(String.class).optional().orElse(null);
            assertThat(body).isEqualTo(
                    "Your $500.00 transfer to Bob Reyes was not sent because there was not enough money in the account.");
        });
    }

    @Test
    void weeklyScheduleRunsAndMovesToNextWeek() {
        String id = (String) schedule(1_000, "WEEKLY", today()).getBody().get("id");

        runner.runDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(row(id).get("runs_completed")).isEqualTo(1));

        assertThat(row(id).get("status")).isEqualTo("ACTIVE");
        OffsetDateTime next = jdbc.sql("SELECT next_run_at FROM scheduled_transfers WHERE id = :id")
                .param("id", UUID.fromString(id)).query(OffsetDateTime.class).single();
        assertThat(next).isBetween(OffsetDateTime.now().plusDays(6), OffsetDateTime.now().plusDays(8));
        assertThat(money.balance(bob, bobWallet)).isEqualTo(1_000);

        // A weekly schedule that was down for a month runs once, not four times.
        jdbc.sql("""
                        UPDATE scheduled_transfers
                        SET start_at = now() - interval '29 days', next_occurrence = 1,
                            next_run_at = now() - interval '22 days'
                        WHERE id = :id
                        """)
                .param("id", UUID.fromString(id)).update();
        runner.runDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(row(id).get("runs_completed")).isEqualTo(2));
        runner.runDue();
        assertThat(money.balance(bob, bobWallet)).isEqualTo(2_000);
        assertThat(row(id).get("next_occurrence")).isEqualTo(5); // 35 days after the start: the next future date
    }

    @Test
    void pausedScheduleDoesNotRunUntilResumedAndCancelStopsIt() {
        String id = (String) schedule(1_500, "ONCE", today().plusDays(1)).getBody().get("id");

        assertThat(api.post(alice.token(), "/api/v1/scheduled-transfers/" + id + "/pause", Map.of())
                .getBody().get("status")).isEqualTo("PAUSED");
        makeDue(id);
        runner.runDue();
        assertThat(row(id).get("status")).isEqualTo("PAUSED");
        assertThat(money.balance(bob, bobWallet)).isZero();

        // Only the owner can touch it.
        assertThat(api.post(bob.token(), "/api/v1/scheduled-transfers/" + id + "/resume", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(api.post(alice.token(), "/api/v1/scheduled-transfers/" + id + "/resume", Map.of())
                .getBody().get("status")).isEqualTo("ACTIVE");
        runner.runDue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(row(id).get("status")).isEqualTo("COMPLETED"));
        assertThat(money.balance(bob, bobWallet)).isEqualTo(1_500);

        // Cancel: never runs, and cancelling twice is harmless.
        String other = (String) schedule(900, "MONTHLY", today().plusDays(3)).getBody().get("id");
        assertThat(api.post(alice.token(), "/api/v1/scheduled-transfers/" + other + "/cancel", Map.of())
                .getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(api.post(alice.token(), "/api/v1/scheduled-transfers/" + other + "/cancel", Map.of())
                .getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(row(other).get("next_run_at")).isNull();
        assertThat(api.post(alice.token(), "/api/v1/scheduled-transfers/" + other + "/resume", Map.of())
                .getBody().get("code")).isEqualTo("SCHEDULE_NOT_PAUSED");

        assertThat(api.getList(alice.token(), "/api/v1/scheduled-transfers").getBody()).hasSize(2);
        assertThat(api.getList(bob.token(), "/api/v1/scheduled-transfers").getBody()).isEmpty();
    }

    @Test
    void severalRunnersAtOnceStillRunAScheduleExactlyOnce() throws Exception {
        String id = (String) schedule(1_000, "ONCE", today().plusDays(1)).getBody().get("id");

        int runners = 6;
        ExecutorService pool = Executors.newFixedThreadPool(runners);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < runners; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return runner.runDue();
            }));
        }
        makeDue(id);
        start.countDown();
        for (Future<Integer> result : results) {
            result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(row(id).get("status")).isEqualTo("COMPLETED"));
        // One transfer, not six.
        Long transfers = jdbc.sql("""
                        SELECT count(*) FROM transactions
                        WHERE source_account_id = :source AND destination_account_id = :destination
                        """)
                .param("source", UUID.fromString(aliceWallet))
                .param("destination", UUID.fromString(bobWallet))
                .query(Long.class).single();
        assertThat(transfers).isEqualTo(1);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(9_000);
        assertThat(money.balance(bob, bobWallet)).isEqualTo(1_000);
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(UUID.fromString(aliceWallet), UUID.fromString(bobWallet)));
    }

    @Test
    void scheduleRulesAreCheckedWhenItIsCreated() {
        assertThat(schedule(1_000, "ONCE", today().minusDays(3)).getBody().get("code")).isEqualTo("START_IN_PAST");
        assertThat(schedule(1_000, "ONCE", today().plusDays(900)).getBody().get("code")).isEqualTo("START_TOO_FAR");
        assertThat(schedule(1_000, "DAILY", today()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // From an account that is not yours: not found, same as if it did not exist.
        ResponseEntity<Map> notMine = api.post(bob.token(), "/api/v1/scheduled-transfers", Map.of(
                "sourceAccountId", aliceWallet, "destinationAccountId", bobWallet,
                "amountMinorUnits", 100, "currency", "USD", "frequency", "ONCE",
                "startDate", today().toString()));
        assertThat(notMine.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // To the platform's own cash account: not a valid destination.
        ResponseEntity<Map> toPlatform = api.post(alice.token(), "/api/v1/scheduled-transfers", Map.of(
                "sourceAccountId", aliceWallet, "destinationAccountId", "00000000-0000-7000-8000-000000000001",
                "amountMinorUnits", 100, "currency", "USD", "frequency", "ONCE",
                "startDate", today().toString()));
        assertThat(toPlatform.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
