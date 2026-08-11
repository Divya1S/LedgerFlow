package com.ledgerflow.common.cache;

import java.util.UUID;

/**
 * Cache key derivation shared by writers (evictors) and readers, so the
 * contexts agree on names without depending on each other.
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** First page of an account's transaction history. */
    public static String accountHistory(UUID accountId) {
        return "hist:" + accountId;
    }
}
