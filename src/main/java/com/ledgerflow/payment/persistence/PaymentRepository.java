package com.ledgerflow.payment.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
