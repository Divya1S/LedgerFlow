package com.ledgerflow.fraud.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read side of fraud verdicts and AI assessments, for surfaces outside the
 * fraud context (the payment API aggregates this into its responses).
 */
@Service
public class FraudAssessments {

    private final JdbcClient jdbc;

    public FraudAssessments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Assessment(String verdict, int score, String ruleHitsJson,
                             String aiAssessmentJson, String aiModel, OffsetDateTime aiAssessedAt) {
    }

    /** The latest verdict for each of the given payments that has one. */
    public Map<UUID, String> verdictsFor(Collection<UUID> paymentIds) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> verdicts = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (payment_id) payment_id, verdict
                        FROM fraud_decisions
                        WHERE payment_id = ANY(:ids)
                        ORDER BY payment_id, evaluated_at DESC
                        """)
                .param("ids", paymentIds.toArray(UUID[]::new))
                .query((rs, n) -> verdicts.put(rs.getObject("payment_id", UUID.class), rs.getString("verdict")))
                .list();
        return verdicts;
    }

    public Optional<Assessment> forPayment(UUID paymentId) {
        return jdbc.sql("""
                        SELECT verdict, score, rule_hits::text AS rule_hits,
                               ai_assessment::text AS ai_assessment, ai_model, ai_assessed_at
                        FROM fraud_decisions
                        WHERE payment_id = :paymentId
                        ORDER BY evaluated_at DESC
                        LIMIT 1
                        """)
                .param("paymentId", paymentId)
                .query((rs, n) -> new Assessment(
                        rs.getString("verdict"),
                        rs.getInt("score"),
                        rs.getString("rule_hits"),
                        rs.getString("ai_assessment"),
                        rs.getString("ai_model"),
                        rs.getObject("ai_assessed_at", OffsetDateTime.class)))
                .optional();
    }
}
