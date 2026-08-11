package com.ledgerflow.outbox;

import java.util.UUID;

import com.ledgerflow.common.id.Uuid7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes outbox rows inside the caller's database transaction. This is the
 * write half of the transactional outbox pattern: the event becomes durable
 * exactly when the state change commits, and the publisher (Phase 5) moves
 * it to Kafka afterwards. Writing to Kafka directly here would let the
 * database commit and the publish fail independently, which is the
 * consistency hole this pattern exists to close.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;

    public OutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID write(String aggregateType, UUID aggregateId, String eventType,
                      String topic, String payloadJson) {
        UUID eventId = Uuid7.generate();
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, :topic, CAST(:payload AS jsonb))
                        """)
                .param("id", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("topic", topic)
                .param("payload", payloadJson)
                .update();
        return eventId;
    }
}
