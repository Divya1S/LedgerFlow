package com.ledgerflow.transactionquery.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ledgerflow.common.db.PartyLabel;
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

    /** A transaction plus how each side should be named to the viewer. */
    public record TransactionDetail(
            TransactionView transaction, String sourceLabel, String destinationLabel,
            boolean sourceOwn, boolean destinationOwn) {
    }

    /** Optional narrowing of a user's history. Null fields do not filter. */
    public record Filter(String type, UUID accountId, String direction,
                         OffsetDateTime from, OffsetDateTime to, String text,
                         Long minAmount, Long maxAmount) {

        public static Filter none() {
            return new Filter(null, null, null, null, null, null, null, null);
        }
    }

    public record StatementLine(
            UUID entryId, UUID transactionId, long amount, String direction,
            long balanceAfter, String currency, OffsetDateTime createdAt,
            String type, String description, String counterparty) {
    }

    /** One leg of the double-entry posting behind a transaction. */
    public record EntryView(UUID accountId, String accountLabel, String accountType, boolean own,
                            long amount, String direction) {
    }

    private static final String TXN_COLUMNS =
            "t.id, t.type, t.status, t.amount, t.currency, t.source_account_id, "
            + "t.destination_account_id, t.description, t.created_at";

    private static final String OWN_ACCOUNTS = "(SELECT id FROM accounts WHERE user_id = :userId)";

    /**
     * Transactions touching the user's accounts, newest first, optionally
     * filtered. UNION ALL of two index-friendly branches (source side,
     * destination side), deduplicated with DISTINCT ON for transfers
     * between the user's own accounts. Filters are pushed into both
     * branches so each still walks its (account, created_at DESC) index
     * and stops after a page; names are joined only for the page's rows.
     */
    public List<TransactionDetail> search(UUID userId, Filter filter,
                                          OffsetDateTime cursorTime, UUID cursorId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("limit", limit);

        StringBuilder shared = new StringBuilder();
        if (cursorTime != null) {
            shared.append(" AND (t.created_at, t.id) < (:cts, :cid)");
            params.put("cts", cursorTime);
            params.put("cid", cursorId);
        }
        if (filter.type() != null) {
            shared.append(" AND t.type = :type");
            params.put("type", filter.type());
        }
        if (filter.from() != null) {
            shared.append(" AND t.created_at >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            shared.append(" AND t.created_at < :to");
            params.put("to", filter.to());
        }
        if (filter.text() != null) {
            shared.append(" AND t.description ILIKE :text ESCAPE '\\'");
            params.put("text", "%" + escapeLike(filter.text()) + "%");
        }
        if (filter.minAmount() != null) {
            shared.append(" AND t.amount >= :minAmount");
            params.put("minAmount", filter.minAmount());
        }
        if (filter.maxAmount() != null) {
            shared.append(" AND t.amount <= :maxAmount");
            params.put("maxAmount", filter.maxAmount());
        }

        boolean oneAccount = filter.accountId() != null;
        if (oneAccount) {
            params.put("accountId", filter.accountId());
        }
        String sourceMatch = oneAccount ? "t.source_account_id = :accountId"
                : "t.source_account_id IN " + OWN_ACCOUNTS;
        String destinationMatch = oneAccount ? "t.destination_account_id = :accountId"
                : "t.destination_account_id IN " + OWN_ACCOUNTS;

        // "Money out" and "money in" mean out of / into the user as a
        // whole, so a move between two of their own accounts is neither.
        // When one account is selected the direction is relative to it.
        List<String> branches = new ArrayList<>();
        if (!"IN".equals(filter.direction())) {
            String leavesUser = "OUT".equals(filter.direction()) && !oneAccount
                    ? " AND t.destination_account_id NOT IN " + OWN_ACCOUNTS : "";
            branches.add("SELECT " + TXN_COLUMNS + " FROM transactions t WHERE "
                    + sourceMatch + shared + leavesUser);
        }
        if (!"OUT".equals(filter.direction())) {
            String entersUser = "IN".equals(filter.direction()) && !oneAccount
                    ? " AND t.source_account_id NOT IN " + OWN_ACCOUNTS : "";
            branches.add("SELECT " + TXN_COLUMNS + " FROM transactions t WHERE "
                    + destinationMatch + shared + entersUser);
        }

        String sql = """
                SELECT p.*,
                       %s AS source_label,
                       %s AS destination_label,
                       COALESCE(sa.user_id = :userId, false) AS source_own,
                       COALESCE(da.user_id = :userId, false) AS destination_own
                FROM (
                    SELECT DISTINCT ON (u.created_at, u.id) u.*
                    FROM (%s) u
                    ORDER BY u.created_at DESC, u.id DESC
                    LIMIT :limit
                ) p
                LEFT JOIN accounts sa ON sa.id = p.source_account_id
                LEFT JOIN users su ON su.id = sa.user_id
                LEFT JOIN accounts da ON da.id = p.destination_account_id
                LEFT JOIN users du ON du.id = da.user_id
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(
                PartyLabel.sql("sa", "su", "userId"),
                PartyLabel.sql("da", "du", "userId"),
                String.join(" UNION ALL ", branches));

        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new TransactionDetail(
                        map(rs, n),
                        rs.getString("source_label"),
                        rs.getString("destination_label"),
                        rs.getBoolean("source_own"),
                        rs.getBoolean("destination_own")))
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
     *
     * The page is still chosen by the ledger index alone. What each line
     * was (type, description, who was on the other side) is looked up by
     * primary key afterwards, for the page's rows only.
     */
    public List<StatementLine> statement(UUID accountId, UUID viewerUserId, long startBalance,
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
                                   0) AS balance_after,
                               %s
                        FROM page p
                        %s
                        ORDER BY p.created_at DESC, p.id DESC
                        """.formatted(keyset, LINE_DETAIL_COLUMNS, LINE_DETAIL_JOINS))
                .param("accountId", accountId)
                .param("viewer", viewerUserId)
                .param("startBalance", startBalance)
                .param("limit", limit)
                .params(cursorParams(cursorTime, cursorId))
                .query(this::mapStatementLine)
                .list();
    }

    /**
     * Every entry of an account in [from, to), newest first, with the
     * balance after each one. closingBalance is the balance right after
     * the newest entry in the range; the caller computes it in the same
     * snapshot. Reads at most maxRows rows.
     */
    public List<StatementLine> statementRange(UUID accountId, UUID viewerUserId, long closingBalance,
                                              OffsetDateTime from, OffsetDateTime to, int maxRows) {
        return jdbc.sql("""
                        WITH page AS (
                            SELECT e.id, e.transaction_id, e.amount, e.direction, e.currency, e.created_at
                            FROM ledger_entries e
                            WHERE e.account_id = :accountId
                              AND e.created_at >= :from AND e.created_at < :to
                            ORDER BY e.created_at DESC, e.id DESC
                            LIMIT :limit
                        )
                        SELECT p.*,
                               :closingBalance - COALESCE(
                                   sum(p.amount) OVER (
                                       ORDER BY p.created_at DESC, p.id DESC
                                       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),
                                   0) AS balance_after,
                               %s
                        FROM page p
                        %s
                        ORDER BY p.created_at DESC, p.id DESC
                        """.formatted(LINE_DETAIL_COLUMNS, LINE_DETAIL_JOINS))
                .param("accountId", accountId)
                .param("viewer", viewerUserId)
                .param("closingBalance", closingBalance)
                .param("from", from)
                .param("to", to)
                .param("limit", maxRows)
                .query(this::mapStatementLine)
                .list();
    }

    /** Net change of an account from a point in time until now. */
    public long netChangeSince(UUID accountId, OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(e.amount), 0)
                        FROM ledger_entries e
                        WHERE e.account_id = :accountId AND e.created_at >= :since
                        """)
                .param("accountId", accountId)
                .param("since", since)
                .query(Long.class)
                .single();
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

    /**
     * The ledger entries of one transaction: the proof that it balances.
     * createdAt is passed so only the one monthly partition is read.
     */
    public List<EntryView> entriesOf(UUID transactionId, OffsetDateTime createdAt, UUID viewerUserId) {
        return jdbc.sql("""
                        SELECT e.account_id, e.amount, e.direction, a.type,
                               COALESCE(a.user_id = :viewer, false) AS own,
                               %s AS account_label
                        FROM ledger_entries e
                        JOIN accounts a ON a.id = e.account_id
                        LEFT JOIN users u ON u.id = a.user_id
                        WHERE e.transaction_id = :transactionId AND e.created_at = :createdAt
                        ORDER BY e.amount, e.id
                        """.formatted(PartyLabel.sql("a", "u", "viewer")))
                .param("transactionId", transactionId)
                .param("createdAt", createdAt)
                .param("viewer", viewerUserId)
                .query((rs, n) -> new EntryView(
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_label"),
                        rs.getString("type"),
                        rs.getBoolean("own"),
                        rs.getLong("amount"),
                        rs.getString("direction")))
                .list();
    }

    // A statement line's counterparty is whoever was on the other side of
    // the entry: the sender for money that arrived, the receiver for money
    // that left.
    private static final String LINE_DETAIL_COLUMNS = """
            t.type, t.description,
            CASE WHEN p.amount > 0 THEN %s ELSE %s END AS counterparty""".formatted(
            PartyLabel.sql("sa", "su", "viewer"), PartyLabel.sql("da", "du", "viewer"));

    private static final String LINE_DETAIL_JOINS = """
            JOIN transactions t ON t.id = p.transaction_id AND t.created_at = p.created_at
            LEFT JOIN accounts sa ON sa.id = t.source_account_id
            LEFT JOIN users su ON su.id = sa.user_id
            LEFT JOIN accounts da ON da.id = t.destination_account_id
            LEFT JOIN users du ON du.id = da.user_id""";

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Map<String, ?> cursorParams(OffsetDateTime cursorTime, UUID cursorId) {
        return cursorTime == null
                ? Map.of()
                : Map.of("cts", cursorTime, "cid", cursorId);
    }

    private StatementLine mapStatementLine(ResultSet rs, int rowNum) throws SQLException {
        return new StatementLine(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("direction"),
                rs.getLong("balance_after"),
                rs.getString("currency"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("type"),
                rs.getString("description"),
                rs.getString("counterparty"));
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
