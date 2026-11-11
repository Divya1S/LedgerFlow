package com.ledgerflow.common.web;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Opaque keyset cursor for newest-first lists: base64 of "timestamp|id".
 * The next page is "rows before this position", read straight off the
 * index, so deep pages cost the same as the first one.
 */
public record PageCursor(OffsetDateTime time, UUID id) {

    public String encode() {
        String raw = time.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the decoded cursor, or null when no cursor was sent. */
    public static PageCursor decodeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf('|');
            return new PageCursor(OffsetDateTime.parse(raw.substring(0, split)),
                    UUID.fromString(raw.substring(split + 1)));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The cursor is not valid");
        }
    }
}
