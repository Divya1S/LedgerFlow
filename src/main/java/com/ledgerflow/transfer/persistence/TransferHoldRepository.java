package com.ledgerflow.transfer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ledgerflow.common.db.PartyLabel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferHoldRepository {

    private final JdbcClient jdbc;

    public TransferHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record HoldRow(UUID id, UUID senderUserId, UUID recipientUserId, UUID sourceAccountId,
                          UUID destinationAccountId, long amount, String currency, String description,
                          String riskLevel, String reasonsJson, String status, OffsetDateTime releaseAt,
                          UUID holdTransactionId, UUID settleTransactionId, OffsetDateTime resolvedAt,
                          OffsetDateTime createdAt, String senderName, String recipientLabel,
                          String sourceName, String destinationName) {
    }

    // recipient_label is how the SENDER should see the recipient: a
    // business by its name, a person by theirs. The sender is always the
    // viewer for that column, so the hold's own sender id is the reference.
    private static final String SELECT = """
            SELECT h.*, h.reasons::text AS reasons_text,
                   su.display_name AS sender_name,
                   sa.name AS source_name,
                   da.name AS destination_name,
                   %s AS recipient_label
            FROM transfer_holds h
            JOIN users su ON su.id = h.sender_user_id
            JOIN accounts sa ON sa.id = h.source_account_id
            JOIN accounts da ON da.id = h.destination_account_id
            LEFT JOIN users du ON du.id = da.user_id
            """.formatted(PartyLabel.sql("da", "du", "nobody"));

    public void insert(UUID id, UUID senderUserId, UUID recipientUserId, UUID sourceAccountId,
                       UUID destinationAccountId, long amount, String currency, String description,
                       String riskLevel, String reasonsJson, OffsetDateTime releaseAt,
                       UUID holdTransactionId, OffsetDateTime holdTransactionCreatedAt) {
        jdbc.sql("""
                        INSERT INTO transfer_holds
                            (id, sender_user_id, recipient_user_id, source_account_id, destination_account_id,
                             amount, currency, description, risk_level, reasons, status, release_at,
                             hold_transaction_id, hold_transaction_created_at)
                        VALUES
                            (:id, :sender, :recipient, :source, :destination,
                             :amount, :currency, :description, :riskLevel, CAST(:reasons AS jsonb), 'HELD', :releaseAt,
                             :holdTxn, :holdTxnCreatedAt)
                        """)
                .param("id", id)
                .param("sender", senderUserId)
                .param("recipient", recipientUserId)
                .param("source", sourceAccountId)
                .param("destination", destinationAccountId)
                .param("amount", amount)
                .param("currency", currency)
                .param("description", description)
                .param("riskLevel", riskLevel)
                .param("reasons", reasonsJson)
                .param("releaseAt", releaseAt)
                .param("holdTxn", holdTransactionId)
                .param("holdTxnCreatedAt", holdTransactionCreatedAt)
                .update();
    }

    public Optional<HoldRow> find(UUID id) {
        return query(SELECT + " WHERE h.id = :id").param("id", id).query(this::map).optional();
    }

    /** Locks the hold so a cancel and a release can never both win. */
    public Optional<HoldRow> lock(UUID id) {
        return query(SELECT + " WHERE h.id = :id FOR UPDATE OF h").param("id", id).query(this::map).optional();
    }

    /**
     * Claims the hold that has been due longest, or nothing. SKIP LOCKED:
     * a second instance polling at the same moment takes the next one, and
     * a hold being cancelled right now is simply passed over.
     */
    public Optional<HoldRow> lockNextDue() {
        return query(SELECT + """
                 WHERE h.status = 'HELD' AND h.release_at <= now()
                 ORDER BY h.release_at
                 LIMIT 1
                 FOR UPDATE OF h SKIP LOCKED
                """).query(this::map).optional();
    }

    public List<HoldRow> listSentBy(UUID senderUserId) {
        return query(SELECT + """
                 WHERE h.sender_user_id = :user
                 ORDER BY (h.status = 'HELD') DESC, h.created_at DESC, h.id DESC
                 LIMIT 100
                """).param("user", senderUserId).query(this::map).list();
    }

    /** Money on its way to this person. Settled holds are ordinary history by then. */
    public List<HoldRow> listArrivingFor(UUID recipientUserId) {
        return query(SELECT + """
                 WHERE h.recipient_user_id = :user AND h.status = 'HELD'
                 ORDER BY h.release_at, h.id
                 LIMIT 100
                """).param("user", recipientUserId).query(this::map).list();
    }

    public void settle(UUID id, String status, UUID settleTransactionId, OffsetDateTime settleTransactionCreatedAt) {
        jdbc.sql("""
                        UPDATE transfer_holds
                        SET status = :status, settle_transaction_id = :txn,
                            settle_transaction_created_at = :txnCreatedAt, resolved_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("txn", settleTransactionId)
                .param("txnCreatedAt", settleTransactionCreatedAt)
                .update();
    }

    /** Pushes a release back after an unexpected error, so one bad row cannot block the queue. */
    public void postpone(UUID id, OffsetDateTime until) {
        jdbc.sql("UPDATE transfer_holds SET release_at = :until WHERE id = :id AND status = 'HELD'")
                .param("id", id)
                .param("until", until)
                .update();
    }

    /**
     * Have these two people completed a movement of money before, in
     * either direction? A hold that was cancelled does not count: only
     * money that actually arrived establishes a relationship.
     *
     * Each branch walks one side's (account, created_at) index. For a
     * person's own history that is a few hundred rows at most; if it ever
     * became hot, a small table of known pairs maintained on release would
     * replace it (noted in ADR-010).
     */
    public boolean haveSettledBefore(UUID userA, UUID userB) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM transactions t
                            WHERE t.type IN ('TRANSFER', 'HOLD_RELEASE', 'PAYMENT')
                              AND t.source_account_id IN (SELECT id FROM accounts WHERE user_id = :a)
                              AND t.destination_account_id IN (SELECT id FROM accounts WHERE user_id = :b)
                        ) OR EXISTS (
                            SELECT 1 FROM transactions t
                            WHERE t.type IN ('TRANSFER', 'HOLD_RELEASE', 'PAYMENT')
                              AND t.source_account_id IN (SELECT id FROM accounts WHERE user_id = :b)
                              AND t.destination_account_id IN (SELECT id FROM accounts WHERE user_id = :a)
                        )
                        """)
                .param("a", userA)
                .param("b", userB)
                .query(Boolean.class)
                .single();
    }

    /**
     * How many OTHER people have had a first payment to this recipient
     * held in the last day. Every first, larger payment creates a hold, so
     * this table is already the record of strangers paying someone.
     */
    public int otherRecentFirstTimeSenders(UUID recipientUserId, UUID excludingSenderUserId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT sender_user_id)
                        FROM transfer_holds
                        WHERE recipient_user_id = :recipient
                          AND sender_user_id <> :sender
                          AND created_at >= now() - interval '24 hours'
                        """)
                .param("recipient", recipientUserId)
                .param("sender", excludingSenderUserId)
                .query(Integer.class)
                .single();
    }

    /** Total still on hold in one currency: what the suspense account must contain. */
    public long totalHeld(String currency) {
        return jdbc.sql("SELECT COALESCE(sum(amount), 0) FROM transfer_holds WHERE status = 'HELD' AND currency = :c")
                .param("c", currency)
                .query(Long.class)
                .single();
    }

    private JdbcClient.StatementSpec query(String sql) {
        // PartyLabel needs a viewer parameter; no viewer owns the destination here.
        return jdbc.sql(sql).param("nobody", null, java.sql.Types.OTHER);
    }

    private HoldRow map(ResultSet rs, int rowNum) throws SQLException {
        return new HoldRow(
                rs.getObject("id", UUID.class),
                rs.getObject("sender_user_id", UUID.class),
                rs.getObject("recipient_user_id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("description"),
                rs.getString("risk_level"),
                rs.getString("reasons_text"),
                rs.getString("status"),
                rs.getObject("release_at", OffsetDateTime.class),
                rs.getObject("hold_transaction_id", UUID.class),
                rs.getObject("settle_transaction_id", UUID.class),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("sender_name"),
                rs.getString("recipient_label"),
                rs.getString("source_name"),
                rs.getString("destination_name"));
    }
}
