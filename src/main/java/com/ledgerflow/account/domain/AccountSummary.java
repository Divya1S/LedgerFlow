package com.ledgerflow.account.domain;

/**
 * An account together with its current balance, read in one query so list
 * screens do not need a balance call per account.
 */
public record AccountSummary(Account account, long balance) {
}
