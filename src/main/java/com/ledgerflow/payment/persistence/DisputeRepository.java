package com.ledgerflow.payment.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DisputeRepository {

    private final JdbcClient jdbc;

    public DisputeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DisputeRow(UUID id, UUID paymentId, UUID openedBy, String status, String reason,
                             String merchantResponse, String resolutionNote,
                             OffsetDateTime createdAt, OffsetDateTime resolvedAt,
                             long amount, long refundedAmount, String currency,
                             UUID payerUserId, String payerName, UUID merchantUserId, String merchantName) {
    }

    private static final String SELECT = """
            SELECT d.id, d.payment_id, d.opened_by, d.status, d.reason, d.merchant_response,
                   d.resolution_note, d.created_at, d.resolved_at,
                   p.amount, p.refunded_amount, p.currency, p.payer_user_id,
                   pu.display_name AS payer_name,
                   m.user_id AS merchant_user_id, m.name AS merchant_name
            FROM disputes d
            JOIN payments p ON p.id = d.payment_id
            JOIN users pu ON pu.id = p.payer_user_id
            JOIN accounts m ON m.id = p.destination_account_id
            """;

    public void insert(UUID id, UUID paymentId, UUID openedBy, String reason) {
        jdbc.sql("""
                        INSERT INTO disputes (id, payment_id, opened_by, status, reason)
                        VALUES (:id, :paymentId, :openedBy, 'OPEN', :reason)
                        """)
                .param("id", id)
                .param("paymentId", paymentId)
                .param("openedBy", openedBy)
                .param("reason", reason)
                .update();
    }

    public Optional<DisputeRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Locks the dispute row so two decisions on it cannot interleave. */
    public Optional<DisputeRow> lockById(UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id FOR UPDATE OF d")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * Disputes the user opened, disputes against their merchant accounts,
     * and for a platform admin also every dispute waiting for a decision.
     */
    public List<DisputeRow> listVisibleTo(UUID userId, boolean admin) {
        return jdbc.sql(SELECT + """
                         WHERE d.opened_by = :userId
                            OR m.user_id = :userId
                            OR (:admin AND d.status = 'UNDER_REVIEW')
                         ORDER BY d.created_at DESC, d.id DESC
                         LIMIT 200
                        """)
                .param("userId", userId)
                .param("admin", admin)
                .query(this::map)
                .list();
    }

    public void escalate(UUID id, String merchantResponse) {
        jdbc.sql("""
                        UPDATE disputes
                        SET status = 'UNDER_REVIEW', merchant_response = :response
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("response", merchantResponse)
                .update();
    }

    public void resolve(UUID id, String status, String note, UUID resolvedBy) {
        jdbc.sql("""
                        UPDATE disputes
                        SET status = :status, resolution_note = :note, resolved_by = :resolvedBy,
                            resolved_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("note", note)
                .param("resolvedBy", resolvedBy)
                .update();
    }

    private DisputeRow map(ResultSet rs, int rowNum) throws SQLException {
        return new DisputeRow(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("opened_by", UUID.class),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getString("merchant_response"),
                rs.getString("resolution_note"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getLong("amount"),
                rs.getLong("refunded_amount"),
                rs.getString("currency"),
                rs.getObject("payer_user_id", UUID.class),
                rs.getString("payer_name"),
                rs.getObject("merchant_user_id", UUID.class),
                rs.getString("merchant_name"));
    }
}
