package com.ledgerflow.transactionquery.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read side for history and statements. All listing queries use keyset
 * pagination on (created_at DESC, id DESC): the cursor row's position is
 * found by index, so deep pages cost the same as the first page. The OFFSET
 * anti-pattern and its measured cost live in docs/query-optimization.md.
 */
@Repository
public class TransactionQueryRepository {

    private final JdbcClient jdbc;

    public TransactionQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TransactionView(
            UUID id, String type, String status, long amount, String currency,
            UUID sourceAccountId, UUID destinationAccountId, String description, OffsetDateTime createdAt) {
    }

    public record StatementLine(
            UUID entryId, UUID transactionId, long amount, String direction,
            long balanceAfter, String currency, OffsetDateTime createdAt) {
    }

    private static final String TXN_COLUMNS =
            "t.id, t.type, t.status, t.amount, t.currency, t.source_account_id, "
            + "t.destination_account_id, t.description, t.created_at";

    /**
     * All transactions touching any of the user's accounts. UNION ALL of two
     * index-friendly branches (source side, destination side), deduplicated
     * with DISTINCT ON for transfers between the user's own accounts.
     */
    public List<TransactionView> findForUser(UUID userId, OffsetDateTime cursorTime, UUID cursorId, int limit) {
        String keysetSource = cursorTime == null ? "" : " AND (t.created_at, t.id) < (:cts, :cid)";
        return jdbc.sql("""
                        SELECT DISTINCT ON (u.created_at, u.id) u.*
                        FROM (
                            SELECT %s FROM transactions t
                            WHERE t.source_account_id IN (SELECT id FROM accounts WHERE user_id = :userId)%s
                            UNION ALL
                            SELECT %s FROM transactions t
                            WHERE t.destination_account_id IN (SELECT id FROM accounts WHERE user_id = :userId)%s
                        ) u
                        ORDER BY u.created_at DESC, u.id DESC
                        LIMIT :limit
                        """.formatted(TXN_COLUMNS, keysetSource, TXN_COLUMNS, keysetSource))
                .param("userId", userId)
                .param("limit", limit)
                .params(cursorParams(cursorTime, cursorId))
                .query(this::map)
                .list();
    }

    public List<TransactionView> findForAccount(UUID accountId, OffsetDateTime cursorTime, UUID cursorId, int limit) {
        String keyset = cursorTime == null ? "" : " AND (t.created_at, t.id) < (:cts, :cid)";
        return jdbc.sql("""
                        SELECT DISTINCT ON (u.created_at, u.id) u.*
                        FROM (
                            SELECT %s FROM transactions t
                            WHERE t.source_account_id = :accountId%s
                            UNION ALL
                            SELECT %s FROM transactions t
                            WHERE t.destination_account_id = :accountId%s
                        ) u
                        ORDER BY u.created_at DESC, u.id DESC
                        LIMIT :limit
                        """.formatted(TXN_COLUMNS, keyset, TXN_COLUMNS, keyset))
                .param("accountId", accountId)
                .param("limit", limit)
                .params(cursorParams(cursorTime, cursorId))
                .query(this::map)
                .list();
    }

    /**
     * Account statement with a running balance, newest first. The running
     * balance is derived from the CURRENT stored balance walking backwards
     * with a window function over just the page, instead of summing the
     * whole history per request. startBalance is the balance immediately
     * after the newest entry of this page; the caller threads it through
     * the cursor for subsequent pages.
     */
    public List<StatementLine> statement(UUID accountId, long startBalance,
                                         OffsetDateTime cursorTime, UUID cursorId, int limit) {
        String keyset = cursorTime == null ? "" : " AND (e.created_at, e.id) < (:cts, :cid)";
        return jdbc.sql("""
                        WITH page AS (
                            SELECT e.id, e.transaction_id, e.amount, e.direction, e.currency, e.created_at
                            FROM ledger_entries e
                            WHERE e.account_id = :accountId%s
                            ORDER BY e.created_at DESC, e.id DESC
                            LIMIT :limit
                        )
                        SELECT p.*,
                               :startBalance - COALESCE(
                                   sum(p.amount) OVER (
                                       ORDER BY p.created_at DESC, p.id DESC
                                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),
                                   0) AS balance_after
                        FROM page p
                        ORDER BY p.created_at DESC, p.id DESC
                        """.formatted(keyset))
                .param("accountId", accountId)
                .param("startBalance", startBalance)
                .param("limit", limit)
                .params(cursorParams(cursorTime, cursorId))
                .query((rs, n) -> new StatementLine(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getLong("amount"),
                        rs.getString("direction"),
                        rs.getLong("balance_after"),
                        rs.getString("currency"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public Optional<TransactionView> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transactions WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public boolean involvesUser(UUID transactionId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM transactions t
                            WHERE t.id = :transactionId
                              AND (t.source_account_id IN (SELECT id FROM accounts WHERE user_id = :userId)
                                OR t.destination_account_id IN (SELECT id FROM accounts WHERE user_id = :userId))
                        )
                        """)
                .param("transactionId", transactionId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    private java.util.Map<String, ?> cursorParams(OffsetDateTime cursorTime, UUID cursorId) {
        return cursorTime == null
                ? java.util.Map.of()
                : java.util.Map.of("cts", cursorTime, "cid", cursorId);
    }

    private TransactionView map(ResultSet rs, int rowNum) throws SQLException {
        return new TransactionView(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("status"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
