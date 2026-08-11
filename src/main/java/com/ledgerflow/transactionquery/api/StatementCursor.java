package com.ledgerflow.transactionquery.api;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Statement cursor: keyset position plus the running balance immediately
 * BEFORE the cursor entry, so the next page can continue the running
 * balance without summing older history.
 */
public record StatementCursor(OffsetDateTime createdAt, UUID id, long balanceBefore) {

    public String encode() {
        String raw = createdAt.toString() + "|" + id + "|" + balanceBefore;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static StatementCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            return new StatementCursor(OffsetDateTime.parse(parts[0]),
                    UUID.fromString(parts[1]), Long.parseLong(parts[2]));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The cursor is not valid");
        }
    }
}
