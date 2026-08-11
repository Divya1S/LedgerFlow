package com.ledgerflow.account.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        UUID id,
        UUID userId,
        String type,
        String currency,
        String status,
        String name,
        OffsetDateTime createdAt) {
}
