package com.ledgerflow.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;

/**
 * Short, readable money actions for integration tests. Every call goes
 * through the real HTTP API with a fresh idempotency key.
 */
public final class Money {

    private final ApiTestClient api;

    public Money(ApiTestClient api) {
        this.api = api;
    }

    public String openWallet(ApiTestClient.Session owner, String name) {
        return (String) api.post(owner.token(), "/api/v1/accounts",
                Map.of("type", "USER_WALLET", "currency", "USD", "name", name)).getBody().get("id");
    }

    public String openShop(ApiTestClient.Session owner, String name, String category) {
        return (String) api.post(owner.token(), "/api/v1/accounts",
                Map.of("type", "MERCHANT", "currency", "USD", "name", name, "category", category))
                .getBody().get("id");
    }

    public ResponseEntity<Map> deposit(ApiTestClient.Session owner, String accountId, long amount) {
        return deposit(owner, accountId, amount, null);
    }

    public ResponseEntity<Map> deposit(ApiTestClient.Session owner, String accountId, long amount, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("amountMinorUnits", amount);
        body.put("currency", "USD");
        if (description != null) {
            body.put("description", description);
        }
        return api.post(owner.token(), "/api/v1/accounts/" + accountId + "/deposits", body, key());
    }

    public ResponseEntity<Map> withdraw(ApiTestClient.Session owner, String accountId, long amount) {
        return api.post(owner.token(), "/api/v1/accounts/" + accountId + "/withdrawals",
                Map.of("amountMinorUnits", amount, "currency", "USD"), key());
    }

    public ResponseEntity<Map> transfer(ApiTestClient.Session sender, String from, String to,
                                        long amount, String description) {
        return api.post(sender.token(), "/api/v1/transfers",
                Map.of("sourceAccountId", from, "destinationAccountId", to,
                        "amountMinorUnits", amount, "currency", "USD", "description", description), key());
    }

    public ResponseEntity<Map> pay(ApiTestClient.Session payer, String from, String shop,
                                   long amount, String description) {
        return api.post(payer.token(), "/api/v1/payments",
                Map.of("sourceAccountId", from, "destinationAccountId", shop,
                        "amountMinorUnits", amount, "currency", "USD", "description", description), key());
    }

    public long balance(ApiTestClient.Session owner, String accountId) {
        return ((Number) api.get(owner.token(), "/api/v1/accounts/" + accountId + "/balance")
                .getBody().get("balanceMinorUnits")).longValue();
    }

    public static String key() {
        return UUID.randomUUID().toString();
    }
}
