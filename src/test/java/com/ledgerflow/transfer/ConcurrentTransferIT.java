package com.ledgerflow.transfer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.common.db.TransientDbRetry;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.identity.domain.IdentityService;
import com.ledgerflow.identity.domain.User;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.LedgerAssertions;
import com.ledgerflow.transfer.domain.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The double-spend proof. Hundreds of transactions race for the same
 * balance rows; afterwards the books must be exactly right:
 * no lost updates, no negative balances, ledger equals balances.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=32")
class ConcurrentTransferIT {

    private static final long TRANSFER_AMOUNT = 10;

    @Autowired
    TransferService transferService;
    @Autowired
    AccountService accountService;
    @Autowired
    IdentityService identityService;
    @Autowired
    TransactionTemplate txTemplate;
    @Autowired
    TransientDbRetry retry;
    @Autowired
    JdbcClient jdbc;

    record Wallet(UUID userId, UUID accountId) {
    }

    private Wallet newFundedWallet(long initialBalance) {
        User user = identityService.register(
                "conc-" + UUID.randomUUID() + "@test.ledgerflow.io", "correct-horse-battery-staple", "Conc");
        UUID accountId = accountService.openAccount(user.id(), "USER_WALLET", "USD", "wallet").id();
        if (initialBalance > 0) {
            txTemplate.executeWithoutResult(s ->
                    transferService.deposit(user.id(), accountId, initialBalance, "USD", "seed", null));
        }
        return new Wallet(user.id(), accountId);
    }

    private long balance(UUID accountId) {
        return jdbc.sql("SELECT balance FROM account_balances WHERE account_id = :id")
                .param("id", accountId).query(Long.class).single();
    }

    @Test
    void oneThousandConcurrentTransfersKeepTheBooksExact() throws Exception {
        int transfers = 1000;
        long initialBalance = TRANSFER_AMOUNT * transfers / 2; // only half can succeed
        Wallet source = newFundedWallet(initialBalance);
        Wallet destination = newFundedWallet(0);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        runConcurrently(transfers, 24, () -> {
            try {
                retry.execute(() -> txTemplate.execute(s ->
                        transferService.transfer(source.userId(), source.accountId(),
                                destination.accountId(), TRANSFER_AMOUNT, "USD", "storm", null)));
                succeeded.incrementAndGet();
            } catch (ApiException e) {
                if ("INSUFFICIENT_FUNDS".equals(e.code())) {
                    insufficient.incrementAndGet();
                } else {
                    unexpected.incrementAndGet();
                }
            } catch (Exception e) {
                unexpected.incrementAndGet();
            }
        });

        assertThat(unexpected).as("transfers failing for any reason but funds").hasValue(0);
        assertThat(succeeded.get() + insufficient.get()).isEqualTo(transfers);
        // Exactly the affordable half must have gone through.
        assertThat(succeeded).hasValue(transfers / 2);
        assertThat(balance(source.accountId())).isZero();
        assertThat(balance(destination.accountId())).isEqualTo(initialBalance);

        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertPerTransactionBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(source.accountId(), destination.accountId()));
    }

    @Test
    void twoConcurrentTransfersCannotDoubleSpendOneBalance() throws Exception {
        Wallet source = newFundedWallet(100);
        Wallet destination = newFundedWallet(0);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        runConcurrently(2, 2, () -> {
            try {
                retry.execute(() -> txTemplate.execute(s ->
                        transferService.transfer(source.userId(), source.accountId(),
                                destination.accountId(), 80, "USD", "double-spend race", null)));
                succeeded.incrementAndGet();
            } catch (ApiException e) {
                if ("INSUFFICIENT_FUNDS".equals(e.code())) {
                    insufficient.incrementAndGet();
                }
            }
        });

        assertThat(succeeded).as("only one 80 transfer fits in a 100 balance").hasValue(1);
        assertThat(insufficient).hasValue(1);
        assertThat(balance(source.accountId())).isEqualTo(20);
        assertThat(balance(destination.accountId())).isEqualTo(80);
    }

    @Test
    void opposingTransferStormsDoNotDeadlockBecauseLockOrderIsSorted() throws Exception {
        Wallet a = newFundedWallet(5_000);
        Wallet b = newFundedWallet(5_000);

        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        // 200 A->B and 200 B->A interleaved. Without deterministic lock
        // ordering this reliably deadlocks; with it, it must not.
        runConcurrently(400, 16, () -> {
            int i = completed.getAndIncrement();
            Wallet from = (i % 2 == 0) ? a : b;
            Wallet to = (i % 2 == 0) ? b : a;
            try {
                txTemplate.execute(s -> transferService.transfer(from.userId(), from.accountId(),
                        to.accountId(), 5, "USD", "bidirectional", null));
            } catch (PessimisticLockingFailureException e) {
                deadlocks.incrementAndGet();
            } catch (ApiException ignored) {
                // insufficient funds is fine here; deadlocks are not
            }
        });

        assertThat(deadlocks).as("deadlocks under sorted lock ordering").hasValue(0);
        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc, List.of(a.accountId(), b.accountId()));
    }

    private void runConcurrently(int tasks, int threads, Runnable task) throws InterruptedException {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(tasks);
            for (int i = 0; i < tasks; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown(); // fire all tasks as simultaneously as possible
            assertThat(done.await(120, TimeUnit.SECONDS)).as("storm finished in time").isTrue();
        }
    }
}
