package com.ledgerflow.account.domain;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ledgerflow.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Looks up the platform's system accounts (cash, fees) per currency. These
 * rows are seeded by migration and never change at runtime, so caching the
 * ids in memory is safe.
 */
@Component
public class SystemAccounts {

    private final JdbcClient jdbc;
    private final Map<String, UUID> cache = new ConcurrentHashMap<>();

    public SystemAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID cashAccount(String currency) {
        return lookup("SYSTEM_CASH", currency);
    }

    public UUID feesAccount(String currency) {
        return lookup("SYSTEM_FEES", currency);
    }

    private UUID lookup(String type, String currency) {
        return cache.computeIfAbsent(type + ":" + currency, key ->
                jdbc.sql("SELECT id FROM accounts WHERE type = :type AND currency = :currency")
                        .param("type", type)
                        .param("currency", currency)
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(() -> ApiException.unprocessable("UNSUPPORTED_CURRENCY",
                                "No system accounts exist for currency " + currency)));
    }
}
