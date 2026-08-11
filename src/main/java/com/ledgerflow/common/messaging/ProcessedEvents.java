package com.ledgerflow.common.messaging;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Consumer-side dedup for at-least-once delivery. The first consumer in a
 * group to process an event id wins the INSERT; redeliveries see a conflict
 * and skip. The insert takes part in the consumer's database transaction,
 * so "marked processed" and "side effects applied" commit atomically.
 */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time the group sees this event. */
    public boolean markProcessed(String consumerGroup, UUID eventId) {
        return jdbc.sql("""
                        INSERT INTO processed_events (consumer_group, event_id)
                        VALUES (:group, :eventId)
                        ON CONFLICT (consumer_group, event_id) DO NOTHING
                        """)
                .param("group", consumerGroup)
                .param("eventId", eventId)
                .update() == 1;
    }
}
