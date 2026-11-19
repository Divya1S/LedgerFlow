package com.ledgerflow.transfer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRequestRepository {

    private final JdbcClient jdbc;

    public PaymentRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RequestRow(UUID id, UUID requesterUserId, String requesterName,
                             UUID destinationAccountId, long amount, String currency, String note,
                             String status, UUID payerUserId, String payerName, UUID transactionId,
                             OffsetDateTime expiresAt, OffsetDateTime paidAt, OffsetDateTime createdAt) {

        /** PENDING requests past their expiry read as EXPIRED; nothing has to sweep them. */
        public String effectiveStatus() {
            return "PENDING".equals(status) && expiresAt.isBefore(OffsetDateTime.now()) ? "EXPIRED" : status;
        }
    }

    private static final String SELECT = """
            SELECT r.*, ru.display_name AS requester_name, pu.display_name AS payer_name
            FROM payment_requests r
            JOIN users ru ON ru.id = r.requester_user_id
            LEFT JOIN users pu ON pu.id = r.payer_user_id
            """;

    public void insert(UUID id, UUID requesterUserId, UUID destinationAccountId, long amount,
                       String currency, String note, OffsetDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO payment_requests
                            (id, requester_user_id, destination_account_id, amount, currency, note, expires_at)
                        VALUES (:id, :requester, :destination, :amount, :currency, :note, :expiresAt)
                        """)
                .param("id", id)
                .param("requester", requesterUserId)
                .param("destination", destinationAccountId)
                .param("amount", amount)
                .param("currency", currency)
                .param("note", note)
                .param("expiresAt", expiresAt)
                .update();
    }

    public Optional<RequestRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Locks the request so it can be paid or cancelled exactly once. */
    public Optional<RequestRow> lockById(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id FOR UPDATE OF r")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<RequestRow> listForRequester(UUID requesterUserId) {
        return jdbc.sql(SELECT + """
                         WHERE r.requester_user_id = :requester
                         ORDER BY r.created_at DESC, r.id DESC
                         LIMIT 200
                        """)
                .param("requester", requesterUserId)
                .query(this::map)
                .list();
    }

    public void markPaid(UUID id, UUID payerUserId, UUID transactionId, OffsetDateTime transactionCreatedAt) {
        jdbc.sql("""
                        UPDATE payment_requests
                        SET status = 'PAID', payer_user_id = :payer, transaction_id = :transactionId,
                            transaction_created_at = :transactionCreatedAt, paid_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("payer", payerUserId)
                .param("transactionId", transactionId)
                .param("transactionCreatedAt", transactionCreatedAt)
                .update();
    }

    /**
     * Undoes markPaid for the request (if any) that was paid by this
     * transaction. Used when the paying transfer was on hold and the money
     * went back to the payer, so the request can be paid again.
     */
    public int reopenPaidBy(UUID transactionId) {
        return jdbc.sql("""
                        UPDATE payment_requests
                        SET status = 'PENDING', payer_user_id = NULL, transaction_id = NULL,
                            transaction_created_at = NULL, paid_at = NULL
                        WHERE transaction_id = :transactionId AND status = 'PAID'
                        """)
                .param("transactionId", transactionId)
                .update();
    }

    public void markCancelled(UUID id) {
        jdbc.sql("UPDATE payment_requests SET status = 'CANCELLED' WHERE id = :id")
                .param("id", id)
                .update();
    }

    private RequestRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("requester_user_id", UUID.class),
                rs.getString("requester_name"),
                rs.getObject("destination_account_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getObject("payer_user_id", UUID.class),
                rs.getString("payer_name"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("paid_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
