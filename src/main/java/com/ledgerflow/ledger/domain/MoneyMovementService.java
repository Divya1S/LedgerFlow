package com.ledgerflow.ledger.domain;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one code path through which money moves. Runs inside the caller's
 * transaction (MANDATORY) so a transfer, its ledger entries, the balance
 * updates, the audit row and the outbox row commit or roll back together.
 *
 * Concurrency strategy (see docs/concurrency.md):
 *  - Lock the touched account_balances rows with SELECT ... FOR UPDATE,
 *    one at a time, in ascending account id order. Every writer uses the
 *    same order, so two transfers touching the same accounts can block
 *    but not deadlock each other.
 *  - Balance checks happen after the lock, so they read the latest
 *    committed state. READ COMMITTED is enough with this locking.
 *  - The CHECK constraint on account_balances and the deferred zero-sum
 *    trigger on ledger_entries remain as database-level backstops.
 */
@Service
public class MoneyMovementService {

    private final JdbcClient jdbc;

    public MoneyMovementService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record MovementResult(UUID transactionId, OffsetDateTime createdAt) {
    }

    private record LockedAccount(UUID id, String status, String currency, String type,
                                 long balance, Long minBalance) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MovementResult post(String type, long amount, String currency,
                               UUID sourceAccountId, UUID destinationAccountId,
                               String description, UUID idempotencyKeyId,
                               List<Posting> postings) {
        long sum = postings.stream().mapToLong(Posting::amount).sum();
        if (sum != 0) {
            // The deferred trigger would catch this at commit; failing fast
            // here gives a clearer error and avoids wasted work.
            throw new IllegalArgumentException("postings must sum to zero, got " + sum);
        }

        Map<UUID, Long> deltaByAccount = new HashMap<>();
        for (Posting posting : postings) {
            deltaByAccount.merge(posting.accountId(), posting.amount(), Long::sum);
        }

        // Deterministic lock order: ascending account id, one row per query.
        List<UUID> lockOrder = deltaByAccount.keySet().stream().sorted().toList();
        Map<UUID, LockedAccount> locked = new HashMap<>();
        for (UUID accountId : lockOrder) {
            locked.put(accountId, lockAccount(accountId));
        }

        for (LockedAccount account : locked.values()) {
            if (!"ACTIVE".equals(account.status())) {
                throw ApiException.conflict("ACCOUNT_NOT_ACTIVE",
                        "Account " + account.id() + " is not active");
            }
            if (!account.currency().equals(currency)) {
                throw ApiException.unprocessable("CURRENCY_MISMATCH",
                        "Account " + account.id() + " does not hold " + currency);
            }
            long newBalance = account.balance() + deltaByAccount.get(account.id());
            if (account.minBalance() != null && newBalance < account.minBalance()) {
                throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                        "Insufficient funds in account " + account.id());
            }
        }

        UUID transactionId = Uuid7.generate();
        OffsetDateTime createdAt = jdbc.sql("""
                        INSERT INTO transactions
                            (id, type, status, amount, currency, source_account_id,
                             destination_account_id, idempotency_key_id, correlation_id, description)
                        VALUES
                            (:id, :type, 'COMPLETED', :amount, :currency, :source, :destination,
                             :idempotencyKeyId, :correlationId, :description)
                        RETURNING created_at
                        """)
                .param("id", transactionId)
                .param("type", type)
                .param("amount", amount)
                .param("currency", currency)
                .param("source", sourceAccountId)
                .param("destination", destinationAccountId)
                .param("idempotencyKeyId", idempotencyKeyId)
                .param("correlationId", com.ledgerflow.common.web.CorrelationIdFilter.current())
                .param("description", description)
                .query(OffsetDateTime.class)
                .single();

        for (Posting posting : postings) {
            jdbc.sql("""
                            INSERT INTO ledger_entries (id, transaction_id, created_at, account_id, amount, currency)
                            VALUES (:id, :transactionId, :createdAt, :accountId, :amount, :currency)
                            """)
                    .param("id", Uuid7.generate())
                    .param("transactionId", transactionId)
                    .param("createdAt", createdAt)
                    .param("accountId", posting.accountId())
                    .param("amount", posting.amount())
                    .param("currency", currency)
                    .update();
        }

        for (Map.Entry<UUID, Long> entry : deltaByAccount.entrySet()) {
            jdbc.sql("""
                            UPDATE account_balances
                            SET balance = balance + :delta, version = version + 1
                            WHERE account_id = :accountId
                            """)
                    .param("delta", entry.getValue())
                    .param("accountId", entry.getKey())
                    .update();
        }

        jdbc.sql("""
                        INSERT INTO transaction_events (id, transaction_id, transaction_created_at, from_status, to_status, reason)
                        VALUES (:id, :transactionId, :createdAt, NULL, 'COMPLETED', 'posted')
                        """)
                .param("id", Uuid7.generate())
                .param("transactionId", transactionId)
                .param("createdAt", createdAt)
                .update();

        return new MovementResult(transactionId, createdAt);
    }

    private LockedAccount lockAccount(UUID accountId) {
        return jdbc.sql("""
                        SELECT a.id, a.status, a.currency, a.type, b.balance, b.min_balance
                        FROM account_balances b
                        JOIN accounts a ON a.id = b.account_id
                        WHERE b.account_id = :id
                        FOR UPDATE OF b
                        """)
                .param("id", accountId)
                .query((rs, n) -> new LockedAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("currency"),
                        rs.getString("type"),
                        rs.getLong("balance"),
                        rs.getObject("min_balance", Long.class)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND",
                        "Account " + accountId + " not found"));
    }
}
