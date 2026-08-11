package com.ledgerflow.fraud;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.common.messaging.ProcessedEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic fraud rules over payment events, fully asynchronous.
 *
 * Boundary rule, deliberately hard: this consumer writes ONLY
 * fraud_decisions (and its dedup marker). It cannot mutate balances,
 * ledger entries or payment state, so a bug here can flag money but never
 * corrupt it. Acting on decisions (holds, manual review queues) is a
 * separate workflow with its own controls.
 *
 * Rules (scores sum; >= 80 REJECTED, >= 50 REVIEW, else APPROVED):
 *  - LARGE_AMOUNT:      amount >= large-amount threshold        (+60)
 *  - VERY_LARGE_AMOUNT: amount >= very-large threshold          (+90)
 *  - HIGH_VELOCITY:     6th+ payment by this payer in 10 min    (+30)
 *  - FAILED_STREAK:     3+ failed payments by payer in 24h      (+30)
 */
@Component
public class FraudConsumer {

    public static final String GROUP = "fraud-service";

    private static final Logger log = LoggerFactory.getLogger(FraudConsumer.class);

    private final ProcessedEvents processedEvents;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final com.ledgerflow.fraud.ai.FraudAnalystService analyst;
    private final long largeAmount;
    private final long veryLargeAmount;

    public FraudConsumer(ProcessedEvents processedEvents, JdbcClient jdbc, ObjectMapper objectMapper,
                         com.ledgerflow.fraud.ai.FraudAnalystService analyst,
                         @Value("${ledgerflow.fraud.large-amount:100000}") long largeAmount,
                         @Value("${ledgerflow.fraud.very-large-amount:500000}") long veryLargeAmount) {
        this.processedEvents = processedEvents;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.analyst = analyst;
        this.largeAmount = largeAmount;
        this.veryLargeAmount = veryLargeAmount;
    }

    @KafkaListener(topics = "payment.events", groupId = GROUP)
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) throws Exception {
        UUID eventId = headerUuid(record, "eventId");
        String eventType = headerText(record, "eventType");
        if (eventId == null || eventType == null) {
            throw new IllegalArgumentException("payment event without eventId/eventType headers");
        }
        if (!"PaymentCompleted".equals(eventType)) {
            return;
        }
        if (!processedEvents.markProcessed(GROUP, eventId)) {
            return;
        }

        JsonNode payload = objectMapper.readTree(record.value());
        UUID paymentId = UUID.fromString(payload.get("paymentId").asText());
        UUID payerUserId = UUID.fromString(payload.get("payerUserId").asText());
        long amount = payload.get("amountMinorUnits").asLong();

        List<String> ruleHits = new ArrayList<>();
        int score = 0;

        if (amount >= veryLargeAmount) {
            ruleHits.add("VERY_LARGE_AMOUNT");
            score += 90;
        } else if (amount >= largeAmount) {
            ruleHits.add("LARGE_AMOUNT");
            score += 60;
        }

        Long recentPayments = jdbc.sql("""
                        SELECT count(*) FROM payments
                        WHERE payer_user_id = :payer AND created_at >= now() - interval '10 minutes'
                        """)
                .param("payer", payerUserId)
                .query(Long.class).single();
        if (recentPayments > 5) {
            ruleHits.add("HIGH_VELOCITY");
            score += 30;
        }

        Long failedRecently = jdbc.sql("""
                        SELECT count(*) FROM payments
                        WHERE payer_user_id = :payer AND status = 'FAILED'
                          AND created_at >= now() - interval '24 hours'
                        """)
                .param("payer", payerUserId)
                .query(Long.class).single();
        if (failedRecently >= 3) {
            ruleHits.add("FAILED_STREAK");
            score += 30;
        }

        String verdict = score >= 80 ? "REJECTED" : score >= 50 ? "REVIEW" : "APPROVED";
        jdbc.sql("""
                        INSERT INTO fraud_decisions (id, payment_id, verdict, score, rule_hits)
                        VALUES (:id, :paymentId, :verdict, :score, CAST(:ruleHits AS jsonb))
                        """)
                .param("id", Uuid7.generate())
                .param("paymentId", paymentId)
                .param("verdict", verdict)
                .param("score", score)
                .param("ruleHits", objectMapper.writeValueAsString(ruleHits))
                .update();

        if (!"APPROVED".equals(verdict)) {
            log.warn("fraud verdict {} (score {}) for payment {}: {}", verdict, score, paymentId, ruleHits);
            // The AI copilot investigates AFTER the verdict commits, off this
            // thread; its absence or failure changes nothing about the verdict.
            String ruleHitsJson = objectMapper.writeValueAsString(ruleHits);
            int finalScore = score;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            analyst.assessAsync(paymentId, verdict, finalScore, ruleHitsJson);
                        }
                    });
        }
    }

    private UUID headerUuid(ConsumerRecord<String, String> record, String name) {
        String text = headerText(record, name);
        return text == null ? null : UUID.fromString(text);
    }

    private String headerText(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
