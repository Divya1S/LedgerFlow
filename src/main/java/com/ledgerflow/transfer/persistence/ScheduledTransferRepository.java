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
public class ScheduledTransferRepository {

    private final JdbcClient jdbc;

    public ScheduledTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ScheduleRow(UUID id, UUID userId, UUID sourceAccountId, UUID destinationAccountId,
                              long amount, String currency, String description, String frequency,
                              OffsetDateTime startAt, int nextOccurrence, OffsetDateTime nextRunAt,
                              String status, int runsCompleted, OffsetDateTime lastRunAt,
                              String lastOutcome, UUID lastTransactionId, OffsetDateTime createdAt,
                              String sourceLabel, String destinationLabel) {
    }

    private static final String SELECT = """
            SELECT s.*,
                   %s AS source_label,
                   %s AS destination_label
            FROM scheduled_transfers s
            JOIN accounts sa ON sa.id = s.source_account_id
            LEFT JOIN users su ON su.id = sa.user_id
            JOIN accounts da ON da.id = s.destination_account_id
            LEFT JOIN users du ON du.id = da.user_id
            """.formatted(PartyLabel.sql("sa", "su", "viewer"), PartyLabel.sql("da", "du", "viewer"));

    public void insert(UUID id, UUID userId, UUID sourceAccountId, UUID destinationAccountId,
                       long amount, String currency, String description, String frequency,
                       OffsetDateTime startAt) {
        jdbc.sql("""
                        INSERT INTO scheduled_transfers
                            (id, user_id, source_account_id, destination_account_id, amount, currency,
                             description, frequency, start_at, next_occurrence, next_run_at, status)
                        VALUES
                            (:id, :userId, :source, :destination, :amount, :currency,
                             :description, :frequency, :startAt, 0, :startAt, 'ACTIVE')
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("source", sourceAccountId)
                .param("destination", destinationAccountId)
                .param("amount", amount)
                .param("currency", currency)
                .param("description", description)
                .param("frequency", frequency)
                .param("startAt", startAt)
                .update();
    }

    public List<ScheduleRow> listForUser(UUID userId) {
        return jdbc.sql(SELECT + """
                         WHERE s.user_id = :viewer
                         ORDER BY (s.status = 'ACTIVE') DESC, s.next_run_at NULLS LAST, s.created_at DESC
                         LIMIT 200
                        """)
                .param("viewer", userId)
                .query(this::map)
                .list();
    }

    public Optional<ScheduleRow> find(UUID id, UUID userId) {
        return jdbc.sql(SELECT + " WHERE s.id = :id AND s.user_id = :viewer")
                .param("id", id)
                .param("viewer", userId)
                .query(this::map)
                .optional();
    }

    /** Locks one of the user's schedules for a status change. */
    public Optional<ScheduleRow> lock(UUID id, UUID userId) {
        return jdbc.sql(SELECT + " WHERE s.id = :id AND s.user_id = :viewer FOR UPDATE OF s")
                .param("id", id)
                .param("viewer", userId)
                .query(this::map)
                .optional();
    }

    /**
     * Claims the most overdue schedule, or nothing. SKIP LOCKED means a
     * second app instance polling at the same moment takes the next row
     * instead of waiting for this one or running it twice.
     */
    public Optional<ScheduleRow> lockNextDue() {
        return jdbc.sql(SELECT + """
                         WHERE s.status = 'ACTIVE' AND s.next_run_at <= now()
                         ORDER BY s.next_run_at
                         LIMIT 1
                         FOR UPDATE OF s SKIP LOCKED
                        """)
                // No person is looking at this row; labels fall back to owner names.
                .param("viewer", null, java.sql.Types.OTHER)
                .query(this::map)
                .optional();
    }

    public void recordRun(UUID id, String status, int nextOccurrence, OffsetDateTime nextRunAt,
                          boolean completed, String outcome, UUID transactionId) {
        jdbc.sql("""
                        UPDATE scheduled_transfers
                        SET status = :status,
                            next_occurrence = :nextOccurrence,
                            next_run_at = :nextRunAt,
                            runs_completed = runs_completed + :completed,
                            last_run_at = now(),
                            last_outcome = :outcome,
                            last_transaction_id = COALESCE(:transactionId, last_transaction_id)
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("nextOccurrence", nextOccurrence)
                .param("nextRunAt", nextRunAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("completed", completed ? 1 : 0)
                .param("outcome", outcome)
                .param("transactionId", transactionId, java.sql.Types.OTHER)
                .update();
    }

    /** Pushes a run back after an unexpected error, without counting it as an attempt. */
    public void postpone(UUID id, OffsetDateTime until, String outcome) {
        jdbc.sql("""
                        UPDATE scheduled_transfers
                        SET next_run_at = :until, last_outcome = :outcome
                        WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("id", id)
                .param("until", until)
                .param("outcome", outcome)
                .update();
    }

    public void updateStatus(UUID id, String status, int nextOccurrence, OffsetDateTime nextRunAt) {
        jdbc.sql("""
                        UPDATE scheduled_transfers
                        SET status = :status, next_occurrence = :nextOccurrence, next_run_at = :nextRunAt
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("nextOccurrence", nextOccurrence)
                .param("nextRunAt", nextRunAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private ScheduleRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduleRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("source_account_id", UUID.class),
                rs.getObject("destination_account_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("description"),
                rs.getString("frequency"),
                rs.getObject("start_at", OffsetDateTime.class),
                rs.getInt("next_occurrence"),
                rs.getObject("next_run_at", OffsetDateTime.class),
                rs.getString("status"),
                rs.getInt("runs_completed"),
                rs.getObject("last_run_at", OffsetDateTime.class),
                rs.getString("last_outcome"),
                rs.getObject("last_transaction_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("source_label"),
                rs.getString("destination_label"));
    }
}
