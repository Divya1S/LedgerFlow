package com.ledgerflow.notification;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.common.messaging.ProcessedEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates user notifications from payment events. Consumes asynchronously,
 * never joins the money transaction, and is idempotent: redelivered events
 * (at-least-once transport) produce exactly one notification row thanks to
 * the processed_events guard inside the same database transaction.
 *
 * Delivery (email/SMS) is honestly out of scope: rows are written as
 * PENDING for a future sender; IN_APP rows are served by the API directly.
 */
@Component
public class NotificationConsumer {

    public static final String GROUP = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final ProcessedEvents processedEvents;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(ProcessedEvents processedEvents, JdbcClient jdbc, ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.events", groupId = GROUP)
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws Exception {
        UUID eventId = header(record, "eventId");
        String eventType = headerText(record, "eventType");
        if (eventId == null || eventType == null) {
            // Malformed by contract; let the error handler route it to DLT.
            throw new IllegalArgumentException("payment event without eventId/eventType headers");
        }
        if (!processedEvents.markProcessed(GROUP, eventId)) {
            log.debug("duplicate delivery of event {}, skipping", eventId);
            return;
        }

        JsonNode payload = objectMapper.readTree(record.value());
        switch (eventType) {
            case "PaymentCompleted" -> insertNotification(
                    UUID.fromString(payload.get("payerUserId").asText()),
                    "PAYMENT_COMPLETED", record.value());
            case "RefundCompleted" -> {
                // Refund events carry the payment id; notify the payer.
                UUID payerId = jdbc.sql("SELECT payer_user_id FROM payments WHERE id = :id")
                        .param("id", UUID.fromString(payload.get("paymentId").asText()))
                        .query(UUID.class)
                        .optional()
                        .orElse(null);
                if (payerId != null) {
                    insertNotification(payerId, "REFUND_COMPLETED", record.value());
                }
            }
            default -> log.debug("no notification rule for event type {}", eventType);
        }
    }

    private void insertNotification(UUID userId, String type, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO notifications (id, user_id, type, channel, status, payload)
                        VALUES (:id, :userId, :type, 'IN_APP', 'PENDING', CAST(:payload AS jsonb))
                        """)
                .param("id", Uuid7.generate())
                .param("userId", userId)
                .param("type", type)
                .param("payload", payloadJson)
                .update();
    }

    private UUID header(ConsumerRecord<String, String> record, String name) {
        String text = headerText(record, name);
        return text == null ? null : UUID.fromString(text);
    }

    private String headerText(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
