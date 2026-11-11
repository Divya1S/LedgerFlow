package com.ledgerflow.notification;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.common.messaging.ProcessedEvents;
import com.ledgerflow.common.money.MoneyFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns committed events into notifications a person can read. Consumes
 * asynchronously, never joins the money transaction, and is idempotent:
 * redelivered events (at-least-once transport) produce exactly one set of
 * notification rows thanks to the processed_events guard inside the same
 * database transaction.
 *
 * Each notification is written with its final wording, so the inbox reads
 * the same even if an account is renamed later.
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

    /** The owner and public name of one side of a movement. */
    private record Party(UUID ownerId, String ownerName, String accountName, String type) {

        /** A business is known by its account name, a person by their own. */
        String label() {
            return "MERCHANT".equals(type) ? accountName : ownerName;
        }
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
            case "PaymentCompleted" -> onPaymentCompleted(payload, record.value());
            case "RefundCompleted" -> onRefundCompleted(payload, record.value());
            case "DisputeOpened" -> onDisputeOpened(payload, record.value());
            case "DisputeUpdated" -> onDisputeUpdated(payload, record.value());
            default -> log.debug("no notification rule for event type {}", eventType);
        }
    }

    /**
     * Transfers and scheduled-transfer failures. Deposits and withdrawals
     * share this topic and need no notification (the person just did them),
     * so they are skipped before the dedup insert: no row per event for
     * traffic this consumer has nothing to say about.
     */
    @KafkaListener(topics = "ledger.events", groupId = GROUP)
    @Transactional
    public void onLedgerEvent(ConsumerRecord<String, String> record) throws Exception {
        UUID eventId = header(record, "eventId");
        String eventType = headerText(record, "eventType");
        if (eventId == null || eventType == null) {
            throw new IllegalArgumentException("ledger event without eventId/eventType headers");
        }
        if (!"TransferCompleted".equals(eventType) && !"ScheduledTransferFailed".equals(eventType)) {
            return;
        }
        if (!processedEvents.markProcessed(GROUP, eventId)) {
            return;
        }

        JsonNode payload = objectMapper.readTree(record.value());
        if ("TransferCompleted".equals(eventType)) {
            onTransferCompleted(payload, record.value());
        } else {
            onScheduledTransferFailed(payload, record.value());
        }
    }

    private void onPaymentCompleted(JsonNode payload, String raw) {
        UUID payerId = UUID.fromString(payload.get("payerUserId").asText());
        Party merchant = party(uuid(payload, "destinationAccountId"));
        String amount = amount(payload);

        String merchantLabel = merchant == null ? "a merchant" : merchant.label();
        String what = amount == null ? "a payment" : amount;
        insert(payerId, "PAYMENT_COMPLETED", "Payment sent",
                "You paid %s %s.".formatted(merchantLabel, what), raw);

        // The merchant hears about it too, unless they paid their own shop.
        if (merchant != null && merchant.ownerId() != null && !merchant.ownerId().equals(payerId)) {
            insert(merchant.ownerId(), "PAYMENT_RECEIVED", "Payment received",
                    "%s paid %s %s.".formatted(userName(payerId), merchant.label(), what), raw);
        }
    }

    private void onRefundCompleted(JsonNode payload, String raw) {
        // Refund events carry the payment id; the payer is who gets told.
        UUID paymentId = uuid(payload, "paymentId");
        var payment = jdbc.sql("""
                        SELECT p.payer_user_id, p.currency, m.name AS merchant_name
                        FROM payments p
                        JOIN accounts m ON m.id = p.destination_account_id
                        WHERE p.id = :id
                        """)
                .param("id", paymentId)
                .query((rs, n) -> new String[]{
                        rs.getString("payer_user_id"), rs.getString("currency"), rs.getString("merchant_name")})
                .optional()
                .orElse(null);
        if (payment == null) {
            return;
        }
        String amount = payload.hasNonNull("amountMinorUnits")
                ? MoneyFormat.format(payload.get("amountMinorUnits").asLong(), payment[1]) : "A refund";
        insert(UUID.fromString(payment[0]), "REFUND_COMPLETED", "Refund received",
                "%s refunded %s to your wallet.".formatted(payment[2], amount), raw);
    }

    private void onDisputeOpened(JsonNode payload, String raw) {
        var dispute = disputeParties(uuid(payload, "disputeId"));
        if (dispute == null || dispute.merchantOwnerId() == null
                || dispute.merchantOwnerId().equals(dispute.payerId())) {
            return;
        }
        insert(dispute.merchantOwnerId(), "DISPUTE_OPENED", "A payment was disputed",
                "%s disputed a %s payment to %s. Review it under Disputes."
                        .formatted(dispute.payerName(), dispute.amount(), dispute.merchantName()), raw);
    }

    private void onDisputeUpdated(JsonNode payload, String raw) {
        var dispute = disputeParties(uuid(payload, "disputeId"));
        if (dispute == null) {
            return;
        }
        String status = payload.path("status").asText("");
        String body = switch (status) {
            case "RESOLVED_CUSTOMER" -> "Your dispute with %s was resolved in your favour. The payment was refunded."
                    .formatted(dispute.merchantName());
            case "RESOLVED_MERCHANT" -> "Your dispute with %s was reviewed and closed without a refund."
                    .formatted(dispute.merchantName());
            case "UNDER_REVIEW" -> "%s did not agree to refund. LedgerFlow is now reviewing your dispute."
                    .formatted(dispute.merchantName());
            default -> null;
        };
        if (body != null) {
            insert(dispute.payerId(), "DISPUTE_UPDATED", "Dispute update", body, raw);
        }
    }

    private void onTransferCompleted(JsonNode payload, String raw) {
        Party source = party(uuid(payload, "sourceAccountId"));
        Party destination = party(uuid(payload, "destinationAccountId"));
        if (source == null || destination == null || destination.ownerId() == null
                || destination.ownerId().equals(source.ownerId())) {
            return; // moving money between your own accounts needs no announcement
        }
        insert(destination.ownerId(), "MONEY_RECEIVED", "Money received",
                "%s sent you %s.".formatted(source.label(), amountOr(payload, "money")), raw);
    }

    private void onScheduledTransferFailed(JsonNode payload, String raw) {
        UUID userId = uuid(payload, "userId");
        if (userId == null) {
            return;
        }
        Party destination = party(uuid(payload, "destinationAccountId"));
        String reason = switch (payload.path("reason").asText("")) {
            case "INSUFFICIENT_FUNDS" -> "there was not enough money in the account";
            case "ACCOUNT_FROZEN" -> "the account is frozen";
            case "ACCOUNT_NOT_ACTIVE" -> "one of the accounts is closed";
            default -> "it was refused";
        };
        insert(userId, "SCHEDULED_TRANSFER_FAILED", "Scheduled transfer did not go through",
                "Your %s transfer to %s was not sent because %s.".formatted(
                        amountOr(payload, "scheduled"),
                        destination == null ? "a recipient" : destination.label(), reason), raw);
    }

    private record DisputeParties(UUID payerId, String payerName, UUID merchantOwnerId,
                                  String merchantName, String amount) {
    }

    private DisputeParties disputeParties(UUID disputeId) {
        if (disputeId == null) {
            return null;
        }
        return jdbc.sql("""
                        SELECT p.payer_user_id, pu.display_name AS payer_name, m.user_id AS merchant_owner,
                               m.name AS merchant_name, p.amount, p.currency
                        FROM disputes d
                        JOIN payments p ON p.id = d.payment_id
                        JOIN users pu ON pu.id = p.payer_user_id
                        JOIN accounts m ON m.id = p.destination_account_id
                        WHERE d.id = :id
                        """)
                .param("id", disputeId)
                .query((rs, n) -> new DisputeParties(
                        rs.getObject("payer_user_id", UUID.class),
                        rs.getString("payer_name"),
                        rs.getObject("merchant_owner", UUID.class),
                        rs.getString("merchant_name"),
                        MoneyFormat.format(rs.getLong("amount"), rs.getString("currency"))))
                .optional()
                .orElse(null);
    }

    private Party party(UUID accountId) {
        if (accountId == null) {
            return null;
        }
        return jdbc.sql("""
                        SELECT a.user_id, a.name, a.type, u.display_name
                        FROM accounts a
                        LEFT JOIN users u ON u.id = a.user_id
                        WHERE a.id = :id
                        """)
                .param("id", accountId)
                .query((rs, n) -> new Party(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("name"),
                        rs.getString("type")))
                .optional()
                .orElse(null);
    }

    private String userName(UUID userId) {
        return jdbc.sql("SELECT display_name FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElse("Someone");
    }

    private static String amount(JsonNode payload) {
        if (!payload.hasNonNull("amountMinorUnits") || !payload.hasNonNull("currency")) {
            return null;
        }
        return MoneyFormat.format(payload.get("amountMinorUnits").asLong(), payload.get("currency").asText());
    }

    private static String amountOr(JsonNode payload, String fallback) {
        String amount = amount(payload);
        return amount == null ? fallback : amount;
    }

    private static UUID uuid(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? UUID.fromString(payload.get(field).asText()) : null;
    }

    private void insert(UUID userId, String type, String title, String body, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO notifications (id, user_id, type, channel, status, payload, title, body)
                        VALUES (:id, :userId, :type, 'IN_APP', 'PENDING', CAST(:payload AS jsonb), :title, :body)
                        """)
                .param("id", Uuid7.generate())
                .param("userId", userId)
                .param("type", type)
                .param("payload", payloadJson)
                .param("title", title)
                .param("body", body)
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
