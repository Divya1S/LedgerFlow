package com.ledgerflow.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The publish half of the transactional outbox. Polls PENDING rows and
 * moves them to Kafka.
 *
 * Delivery semantics: AT LEAST ONCE, on purpose. The send happens before
 * the status update commits, so a crash in between republishes the event
 * on the next poll. Consumers deduplicate on the event id header
 * (processed_events table). Exactly once is not claimed anywhere.
 *
 * FOR UPDATE SKIP LOCKED lets multiple app instances poll concurrently
 * without double-publishing a row while another instance holds it, and
 * without queueing behind each other.
 *
 * Failure handling: exponential backoff per row (next_attempt_at), and
 * after max attempts the row is parked as FAILED, which is the outbox's
 * dead-letter state. Parked rows are visible in metrics and can be
 * requeued by an operator after the underlying issue is fixed.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate txTemplate;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final int maxAttempts;
    private final long sendTimeoutMs;

    private final io.micrometer.core.instrument.Counter publishedCounter;
    private final io.micrometer.core.instrument.Counter failedCounter;
    private final java.util.concurrent.atomic.AtomicLong pendingGauge = new java.util.concurrent.atomic.AtomicLong();

    public OutboxPublisher(JdbcClient jdbc, TransactionTemplate txTemplate,
                           KafkaTemplate<String, String> kafka,
                           io.micrometer.core.instrument.MeterRegistry registry,
                           @Value("${ledgerflow.outbox.batch-size:100}") int batchSize,
                           @Value("${ledgerflow.outbox.max-attempts:10}") int maxAttempts,
                           @Value("${ledgerflow.outbox.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeoutMs = sendTimeoutMs;
        this.publishedCounter = registry.counter("ledgerflow.outbox.events", "result", "published");
        this.failedCounter = registry.counter("ledgerflow.outbox.events", "result", "attempt_failed");
        registry.gauge("ledgerflow.outbox.pending", pendingGauge);
    }

    private record OutboxRow(UUID id, String aggregateType, UUID aggregateId,
                             String eventType, String topic, String payload, int attempts) {
    }

    @Scheduled(fixedDelayString = "${ledgerflow.outbox.poll-interval-ms:500}")
    public void publishPendingBatch() {
        Integer published = txTemplate.execute(status -> {
            List<OutboxRow> batch = jdbc.sql("""
                            SELECT id, aggregate_type, aggregate_id, event_type, topic,
                                   payload::text AS payload, attempts
                            FROM outbox_events
                            WHERE status = 'PENDING' AND next_attempt_at <= now()
                            ORDER BY created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", batchSize)
                    .query(this::map)
                    .list();

            int ok = 0;
            for (OutboxRow row : batch) {
                if (publish(row)) {
                    markPublished(row.id());
                    publishedCounter.increment();
                    ok++;
                } else {
                    markFailedAttempt(row);
                    failedCounter.increment();
                }
            }
            return ok;
        });
        pendingGauge.set(jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
                .query(Long.class).single());
        if (published != null && published > 0) {
            log.debug("published {} outbox events", published);
        }
    }

    private boolean publish(OutboxRow row) {
        try {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(row.topic(), row.aggregateId().toString(), row.payload());
            record.headers().add("eventId", row.id().toString().getBytes());
            record.headers().add("eventType", row.eventType().getBytes());
            record.headers().add("aggregateType", row.aggregateType().getBytes());
            kafka.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("outbox publish failed for event {} (attempt {}): {}",
                    row.id(), row.attempts() + 1, e.getMessage());
            return false;
        }
    }

    private void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED', published_at = now(), attempts = attempts + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    private void markFailedAttempt(OutboxRow row) {
        int attempts = row.attempts() + 1;
        if (attempts >= maxAttempts) {
            log.error("outbox event {} parked as FAILED after {} attempts", row.id(), attempts);
            jdbc.sql("UPDATE outbox_events SET status = 'FAILED', attempts = :attempts WHERE id = :id")
                    .param("attempts", attempts)
                    .param("id", row.id())
                    .update();
        } else {
            // Exponential backoff capped at 60s: 1s, 2s, 4s, ... per row,
            // so one broken event cannot hot-loop the poller.
            jdbc.sql("""
                            UPDATE outbox_events
                            SET attempts = :attempts,
                                next_attempt_at = now() + make_interval(secs => least(power(2, :attempts), 60))
                            WHERE id = :id
                            """)
                    .param("attempts", attempts)
                    .param("id", row.id())
                    .update();
        }
    }

    private OutboxRow map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("topic"),
                rs.getString("payload"),
                rs.getInt("attempts"));
    }
}
