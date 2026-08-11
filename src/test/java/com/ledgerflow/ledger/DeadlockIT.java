package com.ledgerflow.ledger;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.common.db.TransientDbRetry;
import com.ledgerflow.identity.domain.IdentityService;
import com.ledgerflow.identity.domain.User;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces a real deadlock on purpose, shows PostgreSQL kills one of the
 * two transactions with SQLSTATE 40P01, and shows the retry wrapper turns
 * that loss into an eventual success. This is exactly the failure mode the
 * sorted lock ordering in MoneyMovementService prevents.
 */
@IntegrationTest
class DeadlockIT {

    @Autowired
    IdentityService identityService;
    @Autowired
    AccountService accountService;
    @Autowired
    TransactionTemplate txTemplate;
    @Autowired
    TransientDbRetry retry;
    @Autowired
    JdbcClient jdbc;

    private UUID newAccount() {
        User user = identityService.register(
                "dead-" + UUID.randomUUID() + "@test.ledgerflow.io", "correct-horse-battery-staple", "Dead Lock");
        return accountService.openAccount(user.id(), "USER_WALLET", "USD", "wallet").id();
    }

    private void lockBalanceRow(UUID accountId) {
        jdbc.sql("SELECT balance FROM account_balances WHERE account_id = :id FOR UPDATE")
                .param("id", accountId).query(Long.class).single();
    }

    @Test
    void oppositeLockOrdersDeadlockAndPostgresKillsOneTransaction() throws Exception {
        UUID accountA = newAccount();
        UUID accountB = newAccount();

        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);
        AtomicInteger deadlockLosers = new AtomicInteger();
        AtomicInteger winners = new AtomicInteger();

        Runnable lockAThenB = () -> lockPairOrCount(accountA, accountB, bothHoldFirstLock, deadlockLosers, winners);
        Runnable lockBThenA = () -> lockPairOrCount(accountB, accountA, bothHoldFirstLock, deadlockLosers, winners);

        runBoth(lockAThenB, lockBThenA);

        // PostgreSQL's deadlock detector must have sacrificed exactly one.
        assertThat(deadlockLosers).hasValue(1);
        assertThat(winners).hasValue(1);
    }

    @Test
    void retryWrapperRecoversTheDeadlockLoser() throws Exception {
        UUID accountA = newAccount();
        UUID accountB = newAccount();

        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        AtomicInteger outcomes = new AtomicInteger();

        Runnable task1 = () -> {
            retry.execute(() -> txTemplate.execute(s -> {
                lockBalanceRow(accountA);
                awaitOnFirstAttemptOnly(bothHoldFirstLock, firstAttempt);
                lockBalanceRow(accountB);
                return null;
            }));
            outcomes.incrementAndGet();
        };
        Runnable task2 = () -> {
            retry.execute(() -> txTemplate.execute(s -> {
                lockBalanceRow(accountB);
                awaitOnFirstAttemptOnly(bothHoldFirstLock, firstAttempt);
                lockBalanceRow(accountA);
                return null;
            }));
            outcomes.incrementAndGet();
        };

        runBoth(task1, task2);

        // With retry, BOTH transactions complete: the loser reruns after the
        // winner commits and finds the locks free.
        assertThat(outcomes).hasValue(2);
    }

    private void lockPairOrCount(UUID first, UUID second, CyclicBarrier barrier,
                                 AtomicInteger losers, AtomicInteger winners) {
        try {
            txTemplate.execute(s -> {
                lockBalanceRow(first);
                await(barrier);
                lockBalanceRow(second);
                return null;
            });
            winners.incrementAndGet();
        } catch (PessimisticLockingFailureException e) {
            losers.incrementAndGet();
        }
    }

    private void awaitOnFirstAttemptOnly(CyclicBarrier barrier, AtomicBoolean firstAttempt) {
        if (firstAttempt.getAndSet(false) || barrier.getNumberWaiting() > 0) {
            await(barrier);
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barrier failed", e);
        }
    }

    private void runBoth(Runnable a, Runnable b) throws InterruptedException {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch done = new CountDownLatch(2);
            for (Runnable task : new Runnable[]{a, b}) {
                pool.submit(() -> {
                    try {
                        task.run();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).as("deadlock scenario finished").isTrue();
        }
    }
}
