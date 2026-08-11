package com.ledgerflow.common.idempotency;

import com.ledgerflow.common.db.TransientDbRetry;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Function;

/**
 * Controller-side glue for money endpoints: validates the Idempotency-Key
 * header, wraps the whole transaction in transient-error retry, and turns
 * the stored/first response into a ResponseEntity. The Idempotency-Replayed
 * header tells clients when they received a stored response.
 */
@Component
public class IdempotentEndpoint {

    private final IdempotencyService idempotency;
    private final TransientDbRetry retry;

    public IdempotentEndpoint(IdempotencyService idempotency, TransientDbRetry retry) {
        this.idempotency = idempotency;
        this.retry = retry;
    }

    public <T> ResponseEntity<String> run(CurrentUser user, String endpoint, String idempotencyKey,
                                          Object requestBody, int successStatus,
                                          Function<UUID, T> operation) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "An Idempotency-Key header (1-255 chars) is required for this endpoint");
        }
        IdempotencyService.Result result = retry.execute(() ->
                idempotency.execute(user.id(), endpoint, idempotencyKey, requestBody, successStatus, operation));
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(result.bodyJson());
    }
}
