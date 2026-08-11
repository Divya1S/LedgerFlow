package com.ledgerflow.transactionquery.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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

    /** All transactions touching any of the user's accounts, newest first. */
    public List<TransactionView> findForUser(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT t.*
                        FROM transactions t
                        WHERE t.source_account_id IN (SELECT id FROM accounts WHERE user_id = :userId)
                           OR t.destination_account_id IN (SELECT id FROM accounts WHERE user_id = :userId)
                        ORDER BY t.created_at DESC, t.id DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public List<TransactionView> findForAccount(UUID accountId, int limit) {
        return jdbc.sql("""
                        SELECT t.*
                        FROM transactions t
                        WHERE t.source_account_id = :accountId OR t.destination_account_id = :accountId
                        ORDER BY t.created_at DESC, t.id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("limit", limit)
                .query(this::map)
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
