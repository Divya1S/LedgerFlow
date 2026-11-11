package com.ledgerflow.transactionquery.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Aggregates behind the overview screen. Everything is computed from
 * ledger entries, the same rows the balances come from, so the charts can
 * never disagree with the statement.
 *
 * Both queries read ledger_entries through (account_id, created_at DESC)
 * for a bounded time range, so they touch only the user's own rows in the
 * months asked for (partition pruning on created_at).
 *
 * Moves between two of the user's own accounts are left out of "in" and
 * "out": shifting money from one pocket to another is not income or
 * spending.
 */
@Repository
public class InsightsRepository {

    private final JdbcClient jdbc;

    public InsightsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DailyFlow(LocalDate day, long moneyIn, long moneyOut) {
    }

    public record SpendingSlice(String category, long amount, long transactions) {
    }

    private static final String NOT_INTERNAL = """
            NOT (COALESCE(t.source_account_id = ANY(:own), false)
                 AND COALESCE(t.destination_account_id = ANY(:own), false))""";

    /** Money in and out per calendar day, in the viewer's time zone. */
    public List<DailyFlow> dailyFlows(UUID[] ownAccountIds, OffsetDateTime from, OffsetDateTime to, String zone) {
        return jdbc.sql("""
                        SELECT (e.created_at AT TIME ZONE :zone)::date AS day,
                               COALESCE(sum(e.amount) FILTER (WHERE e.amount > 0), 0) AS money_in,
                               COALESCE(-sum(e.amount) FILTER (WHERE e.amount < 0), 0) AS money_out
                        FROM ledger_entries e
                        JOIN transactions t ON t.id = e.transaction_id AND t.created_at = e.created_at
                        WHERE e.account_id = ANY(:own)
                          AND e.created_at >= :from AND e.created_at < :to
                          AND %s
                        GROUP BY 1
                        ORDER BY 1
                        """.formatted(NOT_INTERNAL))
                .param("own", ownAccountIds)
                .param("from", from)
                .param("to", to)
                .param("zone", zone)
                .query((rs, n) -> new DailyFlow(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("money_in"),
                        rs.getLong("money_out")))
                .list();
    }

    /**
     * Where money went. Payments are grouped by what the merchant sells;
     * other outgoing money is grouped by what kind of movement it was.
     */
    public List<SpendingSlice> spending(UUID[] ownAccountIds, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT CASE t.type
                                   WHEN 'PAYMENT' THEN COALESCE(d.category, 'OTHER')
                                   WHEN 'TRANSFER' THEN 'TRANSFERS'
                                   WHEN 'WITHDRAWAL' THEN 'WITHDRAWALS'
                                   WHEN 'REFUND' THEN 'REFUNDS'
                                   ELSE 'OTHER'
                               END AS category,
                               -sum(e.amount) AS amount,
                               count(DISTINCT t.id) AS transactions
                        FROM ledger_entries e
                        JOIN transactions t ON t.id = e.transaction_id AND t.created_at = e.created_at
                        LEFT JOIN accounts d ON d.id = t.destination_account_id
                        WHERE e.account_id = ANY(:own)
                          AND e.amount < 0
                          AND e.created_at >= :from AND e.created_at < :to
                          AND %s
                        GROUP BY 1
                        ORDER BY amount DESC, category
                        """.formatted(NOT_INTERNAL))
                .param("own", ownAccountIds)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new SpendingSlice(
                        rs.getString("category"),
                        rs.getLong("amount"),
                        rs.getLong("transactions")))
                .list();
    }
}
