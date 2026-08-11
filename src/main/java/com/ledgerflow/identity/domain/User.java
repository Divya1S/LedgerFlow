package com.ledgerflow.identity.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record User(
        UUID id,
        String email,
        String passwordHash,
        String fullName,
        String role,
        String status,
        OffsetDateTime createdAt) {
}
