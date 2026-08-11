package com.ledgerflow.account.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Balance(
        UUID accountId,
        long balance,
        String currency,
        OffsetDateTime asOf) {
}
