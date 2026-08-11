package com.ledgerflow.common.error;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The single error shape every endpoint returns.
 * code is stable and machine-readable; message is for humans.
 */
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String code,
        String message,
        String path,
        UUID correlationId,
        List<FieldError> details) {

    public record FieldError(String field, String message) {
    }
}
