package com.ledgerflow.fraud.ai;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The copilot's read-only view of the world. Every method is a SELECT; the
 * model physically cannot mutate anything through this executor, which is
 * the hard guardrail behind "the copilot recommends, humans act".
 */
@Component
public class FraudDataTools implements LlmClient.ToolExecutor {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public FraudDataTools(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public String execute(String toolName, JsonNode arguments) {
        UUID paymentId = UUID.fromString(arguments.path("payment_id").asText());
        Map<String, Object> result = switch (toolName) {
            case "get_payment_details" -> paymentDetails(paymentId);
            case "get_payer_recent_activity" -> payerActivity(paymentId);
            case "get_account_standing" -> accountStanding(paymentId);
            default -> Map.of("error", "unknown tool " + toolName);
        };
        try {
            return json.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    private Map<String, Object> paymentDetails(UUID paymentId) {
        return jdbc.sql("""
                        SELECT p.id, p.amount, p.currency, p.status, p.refunded_amount,
                               p.created_at, p.source_account_id, p.destination_account_id,
                               t.description
                        FROM payments p
                        LEFT JOIN transactions t ON t.id = p.transaction_id
                        WHERE p.id = :id
                        """)
                .param("id", paymentId)
                .query((rs, n) -> Map.<String, Object>of(
                        "payment_id", rs.getString("id"),
                        "amount_minor_units", rs.getLong("amount"),
                        "currency", rs.getString("currency"),
                        "status", rs.getString("status"),
                        "refunded_minor_units", rs.getLong("refunded_amount"),
                        "created_at", rs.getString("created_at"),
                        // Untrusted user input, passed as data (see system prompt).
                        "description_untrusted", String.valueOf(rs.getString("description"))))
                .optional()
                .orElse(Map.of("error", "payment not found"));
    }

    private Map<String, Object> payerActivity(UUID paymentId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE p2.created_at >= now() - interval '10 minutes') AS payments_10m,
                               count(*) FILTER (WHERE p2.created_at >= now() - interval '24 hours')   AS payments_24h,
                               count(*) FILTER (WHERE p2.created_at >= now() - interval '7 days')     AS payments_7d,
                               COALESCE(sum(p2.amount) FILTER (WHERE p2.created_at >= now() - interval '24 hours'), 0) AS volume_24h,
                               count(*) FILTER (WHERE p2.status = 'FAILED'
                                                AND p2.created_at >= now() - interval '24 hours')     AS failed_24h,
                               EXTRACT(DAY FROM now() - u.created_at)::int AS payer_account_age_days
                        FROM payments p
                        JOIN users u ON u.id = p.payer_user_id
                        LEFT JOIN payments p2 ON p2.payer_user_id = p.payer_user_id
                        WHERE p.id = :id
                        GROUP BY u.created_at
                        """)
                .param("id", paymentId)
                .query((rs, n) -> Map.<String, Object>of(
                        "payments_last_10_minutes", rs.getLong("payments_10m"),
                        "payments_last_24_hours", rs.getLong("payments_24h"),
                        "payments_last_7_days", rs.getLong("payments_7d"),
                        "volume_minor_units_24_hours", rs.getLong("volume_24h"),
                        "failed_payments_24_hours", rs.getLong("failed_24h"),
                        "payer_account_age_days", rs.getInt("payer_account_age_days")))
                .optional()
                .orElse(Map.of("error", "payment not found"));
    }

    private Map<String, Object> accountStanding(UUID paymentId) {
        return jdbc.sql("""
                        SELECT a.type, a.status,
                               EXTRACT(DAY FROM now() - a.created_at)::int AS account_age_days,
                               (SELECT count(*) FROM fraud_decisions fd
                                JOIN payments p2 ON p2.id = fd.payment_id
                                WHERE p2.destination_account_id = a.id
                                  AND fd.verdict <> 'APPROVED') AS prior_flags_on_account
                        FROM payments p
                        JOIN accounts a ON a.id = p.destination_account_id
                        WHERE p.id = :id
                        """)
                .param("id", paymentId)
                .query((rs, n) -> Map.<String, Object>of(
                        "merchant_account_type", rs.getString("type"),
                        "merchant_account_status", rs.getString("status"),
                        "merchant_account_age_days", rs.getInt("account_age_days"),
                        "prior_flagged_payments_to_this_account", rs.getLong("prior_flags_on_account")))
                .optional()
                .orElse(Map.of("error", "payment not found"));
    }
}
