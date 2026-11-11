package com.ledgerflow.account;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.LedgerAssertions;
import com.ledgerflow.support.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saved recipients, confirmation of payee, and freezing an account.
 */
@IntegrationTest
class RecipientsAndFreezeIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;
    Money money;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
        money = new Money(api);
    }

    @Test
    void lookupShowsANameAndNothingElse() {
        ApiTestClient.Session alice = api.registerNamed("Alice Stone");
        ApiTestClient.Session bob = api.registerNamed("Bob Reyes");
        String bobWallet = money.openWallet(bob, "Bob's secret label");
        String bobShop = money.openShop(bob, "Corner Grocery", "GROCERIES");
        money.deposit(bob, bobWallet, 99_000);

        // A wallet is shown by its owner's name, never by the owner's private label.
        @SuppressWarnings("unchecked")
        Map<String, Object> wallet = api.get(alice.token(), "/api/v1/accounts/lookup/" + bobWallet).getBody();
        assertThat(wallet.get("displayName")).isEqualTo("Bob Reyes");
        assertThat(wallet.get("type")).isEqualTo("USER_WALLET");
        assertThat(wallet.get("own")).isEqualTo(false);
        assertThat(wallet).doesNotContainKeys("balanceMinorUnits", "email", "name", "userId");

        // A merchant account is shown by its business name.
        Map<?, ?> shop = api.get(alice.token(), "/api/v1/accounts/lookup/" + bobShop).getBody();
        assertThat(shop.get("displayName")).isEqualTo("Corner Grocery");
        assertThat(shop.get("category")).isEqualTo("GROCERIES");

        // Platform accounts and unknown ids are simply not found.
        assertThat(api.get(alice.token(), "/api/v1/accounts/lookup/00000000-0000-7000-8000-000000000001")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get(alice.token(), "/api/v1/accounts/lookup/" + UUID.randomUUID())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recipientsCanBeSavedListedAndRemoved() {
        ApiTestClient.Session alice = api.registerNamed("Alice Stone");
        ApiTestClient.Session bob = api.registerNamed("Bob Reyes");
        ApiTestClient.Session mallory = api.registerNamed("Mallory Kent");
        String aliceWallet = money.openWallet(alice, "Everyday");
        String bobWallet = money.openWallet(bob, "Bob wallet");

        ResponseEntity<Map> saved = api.post(alice.token(), "/api/v1/recipients",
                Map.of("accountId", bobWallet, "nickname", "Bob (rent)"));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String recipientId = (String) saved.getBody().get("id");
        assertThat(saved.getBody().get("nickname")).isEqualTo("Bob (rent)");
        assertThat(saved.getBody().get("displayName")).isEqualTo("Bob Reyes");

        // Saving the same account twice is a conflict, decided by the unique constraint.
        assertThat(api.post(alice.token(), "/api/v1/recipients", Map.of("accountId", bobWallet))
                .getBody().get("code")).isEqualTo("RECIPIENT_EXISTS");
        // Your own accounts are not recipients.
        assertThat(api.post(alice.token(), "/api/v1/recipients", Map.of("accountId", aliceWallet))
                .getBody().get("code")).isEqualTo("OWN_ACCOUNT");
        // Without a nickname the payee's own name is used.
        ApiTestClient.Session carol = api.registerNamed("Carol Diaz");
        String carolWallet = money.openWallet(carol, "wallet");
        assertThat(api.post(alice.token(), "/api/v1/recipients", Map.of("accountId", carolWallet))
                .getBody().get("nickname")).isEqualTo("Carol Diaz");

        List<?> list = api.getList(alice.token(), "/api/v1/recipients").getBody();
        assertThat(list).hasSize(2);
        // Recipients are private to whoever saved them.
        assertThat(api.getList(mallory.token(), "/api/v1/recipients").getBody()).isEmpty();
        assertThat(api.delete(mallory.token(), "/api/v1/recipients/" + recipientId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(api.delete(alice.token(), "/api/v1/recipients/" + recipientId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.getList(alice.token(), "/api/v1/recipients").getBody()).hasSize(1);
    }

    @Test
    void frozenAccountCanReceiveButNotSend() {
        ApiTestClient.Session alice = api.registerNamed("Alice Stone");
        ApiTestClient.Session bob = api.registerNamed("Bob Reyes");
        String aliceWallet = money.openWallet(alice, "Everyday");
        String bobWallet = money.openWallet(bob, "Bob wallet");
        money.deposit(alice, aliceWallet, 10_000);
        money.deposit(bob, bobWallet, 10_000);

        // Only the owner can freeze.
        assertThat(api.post(bob.token(), "/api/v1/accounts/" + aliceWallet + "/freeze", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> frozen = api.post(alice.token(), "/api/v1/accounts/" + aliceWallet + "/freeze", Map.of());
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(frozen.getBody().get("status")).isEqualTo("FROZEN");
        assertThat(((Number) frozen.getBody().get("balanceMinorUnits")).longValue()).isEqualTo(10_000);
        // Freezing twice is harmless.
        assertThat(api.post(alice.token(), "/api/v1/accounts/" + aliceWallet + "/freeze", Map.of())
                .getBody().get("status")).isEqualTo("FROZEN");

        // Money cannot leave: transfer and withdrawal are both refused.
        ResponseEntity<Map> blocked = money.transfer(alice, aliceWallet, bobWallet, 1_000, "should not move");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("code")).isEqualTo("ACCOUNT_FROZEN");
        assertThat(money.withdraw(alice, aliceWallet, 1_000).getBody().get("code")).isEqualTo("ACCOUNT_FROZEN");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(10_000);

        // Money can still arrive.
        assertThat(money.transfer(bob, bobWallet, aliceWallet, 2_500, "still lands").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(12_500);

        // Unfrozen, it works again.
        assertThat(api.post(alice.token(), "/api/v1/accounts/" + aliceWallet + "/unfreeze", Map.of())
                .getBody().get("status")).isEqualTo("ACTIVE");
        assertThat(money.transfer(alice, aliceWallet, bobWallet, 1_000, "moves now").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(11_500);

        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(UUID.fromString(aliceWallet), UUID.fromString(bobWallet)));
    }

    @Test
    void accountsListCarriesBalancesAndCanBeRenamed() {
        ApiTestClient.Session alice = api.registerNamed("Alice Stone");
        String wallet = money.openWallet(alice, "Everyday");
        String shop = money.openShop(alice, "Stone Books", "SHOPPING");
        money.deposit(alice, wallet, 4_200);

        List<?> accounts = api.getList(alice.token(), "/api/v1/accounts").getBody();
        assertThat(accounts).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) accounts.get(0);
        assertThat(first.get("id")).isEqualTo(wallet);
        assertThat(((Number) first.get("balanceMinorUnits")).longValue()).isEqualTo(4_200);
        assertThat(first.get("category")).isNull(); // wallets have no category
        assertThat(((Map<?, ?>) accounts.get(1)).get("category")).isEqualTo("SHOPPING");

        ResponseEntity<Map> renamed = api.patch(alice.token(), "/api/v1/accounts/" + wallet,
                Map.of("name", "Rainy \"day\" fund"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("name")).isEqualTo("Rainy \"day\" fund");

        // The quote in the name went into the audit trail as data, not as JSON syntax.
        String auditedName = jdbc.sql("""
                        SELECT new_state->>'name' FROM audit_logs
                        WHERE action = 'ACCOUNT_RENAMED' AND resource_id = :id
                        """)
                .param("id", wallet).query(String.class).single();
        assertThat(auditedName).isEqualTo("Rainy \"day\" fund");

        // A category that is not on the list is refused.
        assertThat(api.post(alice.token(), "/api/v1/accounts",
                Map.of("type", "MERCHANT", "currency", "USD", "name", "x", "category", "WEAPONS"))
                .getBody().get("code")).isEqualTo("UNSUPPORTED_CATEGORY");
        assertThat(shop).isNotNull();
    }
}
