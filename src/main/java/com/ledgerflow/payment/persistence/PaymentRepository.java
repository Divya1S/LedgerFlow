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
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PaymentRow(UUID id, UUID payerUserId, UUID sourceAccountId, UUID destinationAccountId,
                             long amount, long refundedAmount, String currency, String status,
                             UUID transactionId, OffsetDateTime createdAt) {
    }

    /** A payment as a list shows it: who paid whom, for what, and any dispute. */
    public record PaymentListRow(PaymentRow payment, String description, String payerName,
                                 String merchantName, String merchantCategory, UUID merchantUserId,
                                 UUID disputeId, String disputeStatus) {
    }

    public void insertPending(UUID id, UUID payerUserId, UUID sourceAccountId,
                              UUID destinationAccountId, long amount, String currency) {
        jdbc.sql("""
                        INSERT INTO payments
                            (id, payer_user_id, source_account_id, destination_account_id,
                             amount, currency, status)
                        VALUES (:id, :payer, :source, :destination, :amount, :currency, 'PENDING')
                        """)
                .param("id", id)
                .param("payer", payerUserId)
                .param("source", sourceAccountId)
                .param("destination", destinationAccountId)
                .param("amount", amount)
                .param("currency", currency)
                .update();
    }

    public void markCompleted(UUID id, UUID transactionId, OffsetDateTime transactionCreatedAt) {
        jdbc.sql("""
                        UPDATE payments
                        SET status = 'COMPLETED', transaction_id = :transactionId,
                            transaction_created_at = :transactionCreatedAt
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("transactionId", transactionId)
                .param("transactionCreatedAt", transactionCreatedAt)
                .update();
    }

    public Optional<PaymentRow> findById(UUID id) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Locks the payment row so concurrent refunds serialize on it. */
    public Optional<PaymentRow> lockById(UUID id) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * Payments the user made (asMerchant = false) or received on their
     * merchant accounts (asMerchant = true), newest first, keyset paged.
     * Each side reads its own index: (payer_user_id, created_at DESC) or
     * (destination_account_id, created_at DESC, id DESC). The description
     * and the latest dispute are looked up for the page's rows only.
     */
    public List<PaymentListRow> list(UUID userId, boolean asMerchant,
                                     OffsetDateTime cursorTime, UUID cursorId, int limit) {
        String side = asMerchant
                ? "p.destination_account_id IN (SELECT id FROM accounts WHERE user_id = :userId)"
                : "p.payer_user_id = :userId";
        String keyset = cursorTime == null ? "" : " AND (p.created_at, p.id) < (:cts, :cid)";
        var spec = jdbc.sql("""
                        SELECT p.*, t.description,
                               pu.display_name AS payer_name,
                               m.name AS merchant_name, m.category AS merchant_category,
                               m.user_id AS merchant_user_id,
                               d.id AS dispute_id, d.status AS dispute_status
                        FROM (
                            SELECT * FROM payments p
                            WHERE %s%s
                            ORDER BY p.created_at DESC, p.id DESC
                            LIMIT :limit
                        ) p
                        JOIN users pu ON pu.id = p.payer_user_id
                        JOIN accounts m ON m.id = p.destination_account_id
                        LEFT JOIN transactions t
                               ON t.id = p.transaction_id AND t.created_at = p.transaction_created_at
                        LEFT JOIN LATERAL (
                            SELECT id, status FROM disputes
                            WHERE payment_id = p.id
                            ORDER BY created_at DESC
                            LIMIT 1
                        ) d ON true
                        ORDER BY p.created_at DESC, p.id DESC
                        """.formatted(side, keyset))
                .param("userId", userId)
                .param("limit", limit);
        if (cursorTime != null) {
            spec = spec.param("cts", cursorTime).param("cid", cursorId);
        }
        return spec.query((rs, n) -> new PaymentListRow(
                        map(rs, n),
                        rs.getString("description"),
                        rs.getString("payer_name"),
                        rs.getString("merchant_name"),
                        rs.getString("merchant_category"),
                        rs.getObject("merchant_user_id", UUID.class),
                        rs.getObject("dispute_id", UUID.class),
                        rs.getString("dispute_status")))
                .list();
    }

    public void applyRefund(UUID id, long refundAmount, String newStatus) {
        jdbc.sql("""
                        UPDATE payments
                        SET refunded_amount = refunded_amount + :refundAmount, status = :status
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("refundAmount", refundAmount)
                .param("status", newStatus)
                .update();
    }

    public void insertRefund(UUID refundId, UUID paymentId, long amount, String reason,
                             UUID transactionId, OffsetDateTime transactionCreatedAt) {
        jdbc.sql("""
                        INSERT INTO refunds (id, payment_id, amount, status, reason, transaction_id, transaction_created_at)
                        VALUES (:id, :paymentId, :amount, 'COMPLETED', :reason, :transactionId, :transactionCreatedAt)
                        """)
                .param("id", refundId)
                .param("paymentId", paymentId)
                .param("amount", amount)
                .param("reason", reason)
                .param("transactionId", transactionId)
                .param("transactionCreatedAt", transactionCreatedAt)
                .update();
    }

    private PaymentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("payer_user_id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getLong("amount"),
                rs.getLong("refunded_amount"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
