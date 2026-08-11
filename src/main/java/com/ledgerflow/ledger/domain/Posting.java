package com.ledgerflow.ledger.domain;

import java.util.UUID;

/**
 * One signed leg of a balanced movement. Positive credits the account,
 * negative debits it. A movement's postings must sum to zero.
 */
public record Posting(UUID accountId, long amount) {
}
