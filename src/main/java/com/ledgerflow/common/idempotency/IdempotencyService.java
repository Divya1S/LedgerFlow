package com.ledgerflow.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once semantics per (user, endpoint, key) for the API, enforced by
 * the unique constraint on idempotency_keys.
 *
 * Everything runs in ONE database transaction: claim the key, run the
 * operation, store the response. Consequences, all deliberate:
 *
 *  - Crash mid-operation: the claim rolls back with the operation, so a
 *    retry re-executes cleanly. No stuck IN_PROGRESS rows after restart.
 *  - Concurrent duplicate: its INSERT blocks on the unique index until the
 *    first transaction finishes, then either sees the committed row (and
 *    replays the stored response) or gets to execute (if the first rolled
 *    back). The database serializes the race; no duplicate side effects.
 *  - Business failure: the whole transaction rolls back, nothing is stored
 *    under the key, and a retry re-executes. Only successful operations are
 *    recorded and replayed.
 *
 * Key reuse with a different request body is rejected (the stored request
 * hash must match), because silently replaying a response for different
 * input hides client bugs.
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Result(int status, String bodyJson, boolean replayed) {
    }

    @Transactional
    public <T> Result execute(UUID userId, String endpoint, String idempotencyKey,
                              Object requestBody, int successStatus,
                              Function<UUID, T> operation) {
        String requestHash = hash(endpoint, requestBody);
        UUID keyId = Uuid7.generate();

        boolean claimed = jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (id, user_id, endpoint, idem_key, request_hash, status, expires_at)
                        VALUES
                            (:id, :userId, :endpoint, :key, :hash, 'IN_PROGRESS', now() + interval '24 hours')
                        ON CONFLICT (user_id, endpoint, idem_key) DO NOTHING
                        """)
                .param("id", keyId)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .update() == 1;

        if (!claimed) {
            return replay(userId, endpoint, idempotencyKey, requestHash);
        }

        T result = operation.apply(keyId);
        String bodyJson = toJson(result);
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED', response_status = :status, response_body = CAST(:body AS jsonb)
                        WHERE id = :id
                        """)
                .param("status", successStatus)
                .param("body", bodyJson)
                .param("id", keyId)
                .update();
        return new Result(successStatus, bodyJson, false);
    }

    private Result replay(UUID userId, String endpoint, String idempotencyKey, String requestHash) {
        var row = jdbc.sql("""
                        SELECT request_hash, status, response_status, response_body::text AS body
                        FROM idempotency_keys
                        WHERE user_id = :userId AND endpoint = :endpoint AND idem_key = :key
                        """)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("key", idempotencyKey)
                .query((rs, n) -> new String[]{
                        rs.getString("request_hash"), rs.getString("status"),
                        String.valueOf(rs.getInt("response_status")), rs.getString("body")})
                .optional()
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE",
                        "Request with this idempotency key is being processed, retry shortly"));

        if (!row[0].equals(requestHash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used with a different request body");
        }
        if (!"COMPLETED".equals(row[1])) {
            // Defensive: with single-transaction semantics a visible row is
            // COMPLETED; anything else means an async flow owns the key.
            throw ApiException.conflict("REQUEST_IN_PROGRESS",
                    "Request with this idempotency key is still being processed");
        }
        return new Result(Integer.parseInt(row[2]), row[3], true);
    }

    private String hash(String endpoint, Object requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(endpoint.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(objectMapper.writeValueAsBytes(requestBody));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash request", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response for idempotent storage", e);
        }
    }
}
