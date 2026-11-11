package com.ledgerflow.account.domain;

import java.util.UUID;

/**
 * What a sender is allowed to learn about an account before paying it:
 * enough to confirm "this is who I meant", and nothing else. No balance,
 * no email, no other accounts.
 */
public record Payee(
        UUID accountId,
        String type,
        String status,
        String currency,
        String displayName,
        String category,
        boolean own) {
}
