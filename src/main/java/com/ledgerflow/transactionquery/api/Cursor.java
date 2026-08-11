package com.ledgerflow.transactionquery.api;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Opaque keyset cursor: base64 of "created_at|id". Keyset pagination reads
 * "rows before this position" straight off the (created_at, id) ordering,
 * so page 10000 costs the same as page 1. OFFSET pagination must count and
 * discard every skipped row; the measured difference is in
 * docs/query-optimization.md.
 */
public record Cursor(OffsetDateTime createdAt, UUID id) {

    public String encode() {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf('|');
            return new Cursor(OffsetDateTime.parse(raw.substring(0, split)),
                    UUID.fromString(raw.substring(split + 1)));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The cursor is not valid");
        }
    }
}
