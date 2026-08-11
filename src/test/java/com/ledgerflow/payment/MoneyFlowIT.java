package com.ledgerflow.payment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.LedgerAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full payment lifecycle over HTTP: deposit, transfer, payment with fee,
 * partial refund, full refund, and the failure paths. Balances and ledger
 * are checked at every step.
 */
@IntegrationTest
class MoneyFlowIT {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    private String openAccount(ApiTestClient.Session session, String type, String name) {
        return (String) api.post(session.token(), "/api/v1/accounts",
                Map.of("type", type, "currency", "USD", "name", name)).getBody().get("id");
    }

    private long balance(String token, String accountId) {
        return ((Number) api.get(token, "/api/v1/accounts/" + accountId + "/balance")
                .getBody().get("balanceMinorUnits")).longValue();
    }

    private ResponseEntity<Map> withKey(ApiTestClient.Session s, String path, Map<String, Object> body) {
        return api.post(s.token(), path, body, UUID.randomUUID().toString());
    }

    @Test
    void depositTransferPaymentRefundLifecycle() {
        ApiTestClient.Session alice = api.registerAndLogin();
        ApiTestClient.Session bob = api.registerAndLogin();
        String aliceWallet = openAccount(alice, "USER_WALLET", "Alice wallet");
        String bobWallet = openAccount(bob, "USER_WALLET", "Bob wallet");
        String bobShop = openAccount(bob, "MERCHANT", "Bob's shop");

        // Deposit 100.00 into Alice's wallet.
        ResponseEntity<Map> deposit = withKey(alice, "/api/v1/accounts/" + aliceWallet + "/deposits",
                Map.of("amountMinorUnits", 10_000, "currency", "USD"));
        assertThat(deposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(alice.token(), aliceWallet)).isEqualTo(10_000);

        // Alice sends Bob 20.00 wallet to wallet.
        ResponseEntity<Map> transfer = withKey(alice, "/api/v1/transfers",
                Map.of("sourceAccountId", aliceWallet, "destinationAccountId", bobWallet,
                        "amountMinorUnits", 2_000, "currency", "USD", "description", "lunch"));
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(alice.token(), aliceWallet)).isEqualTo(8_000);
        assertThat(balance(bob.token(), bobWallet)).isEqualTo(2_000);

        // Alice pays Bob's shop 50.00; the platform takes 1% (50).
        ResponseEntity<Map> payment = withKey(alice, "/api/v1/payments",
                Map.of("sourceAccountId", aliceWallet, "destinationAccountId", bobShop,
                        "amountMinorUnits", 5_000, "currency", "USD", "description", "order 42"));
        assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String paymentId = (String) payment.getBody().get("paymentId");
        assertThat(((Number) payment.getBody().get("feeMinorUnits")).longValue()).isEqualTo(50);
        assertThat(balance(alice.token(), aliceWallet)).isEqualTo(3_000);
        assertThat(balance(bob.token(), bobShop)).isEqualTo(4_950);

        // Paying a wallet (not a merchant) is rejected.
        ResponseEntity<Map> toWallet = withKey(alice, "/api/v1/payments",
                Map.of("sourceAccountId", aliceWallet, "destinationAccountId", bobWallet,
                        "amountMinorUnits", 100, "currency", "USD"));
        assertThat(toWallet.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(toWallet.getBody().get("code")).isEqualTo("NOT_A_MERCHANT");

        // The payer cannot refund; the merchant can. Partial refund of 20.00:
        // fee comes back proportionally (20 of the 50 fee).
        ResponseEntity<Map> payerRefund = withKey(alice, "/api/v1/payments/" + paymentId + "/refunds",
                Map.of("amountMinorUnits", 2_000));
        assertThat(payerRefund.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> partial = withKey(bob, "/api/v1/payments/" + paymentId + "/refunds",
                Map.of("amountMinorUnits", 2_000, "reason", "one item returned"));
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(partial.getBody().get("paymentStatus")).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(balance(alice.token(), aliceWallet)).isEqualTo(5_000);
        assertThat(balance(bob.token(), bobShop)).isEqualTo(4_950 - 1_980);

        // Refund the remaining 30.00.
        ResponseEntity<Map> rest30 = withKey(bob, "/api/v1/payments/" + paymentId + "/refunds",
                Map.of());
        assertThat(rest30.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rest30.getBody().get("paymentStatus")).isEqualTo("REFUNDED");
        assertThat(balance(alice.token(), aliceWallet)).isEqualTo(8_000);
        assertThat(balance(bob.token(), bobShop)).isZero();

        // A refunded payment cannot be refunded again.
        ResponseEntity<Map> again = withKey(bob, "/api/v1/payments/" + paymentId + "/refunds",
                Map.of("amountMinorUnits", 100));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Withdraw more than the balance fails; a valid withdrawal works.
        ResponseEntity<Map> overdraw = withKey(alice, "/api/v1/accounts/" + aliceWallet + "/withdrawals",
                Map.of("amountMinorUnits", 999_999, "currency", "USD"));
        assertThat(overdraw.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overdraw.getBody().get("code")).isEqualTo("INSUFFICIENT_FUNDS");

        ResponseEntity<Map> withdrawal = withKey(alice, "/api/v1/accounts/" + aliceWallet + "/withdrawals",
                Map.of("amountMinorUnits", 8_000, "currency", "USD"));
        assertThat(withdrawal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(alice.token(), aliceWallet)).isZero();

        // History now shows movements, and the books are exact.
        List history = api.getList(alice.token(), "/api/v1/transactions").getBody();
        assertThat(history).isNotEmpty();

        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertPerTransactionBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc, List.of(
                UUID.fromString(aliceWallet), UUID.fromString(bobWallet), UUID.fromString(bobShop)));

        // Outbox rows were written in the same transactions as the money.
        Long outboxRows = jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
                .query(Long.class).single();
        assertThat(outboxRows).isGreaterThanOrEqualTo(6);
    }

    @Test
    void getPaymentIsVisibleToPayerAndMerchantOnly() {
        ApiTestClient.Session alice = api.registerAndLogin();
        ApiTestClient.Session bob = api.registerAndLogin();
        ApiTestClient.Session mallory = api.registerAndLogin();
        String aliceWallet = openAccount(alice, "USER_WALLET", "wallet");
        String bobShop = openAccount(bob, "MERCHANT", "shop");

        withKey(alice, "/api/v1/accounts/" + aliceWallet + "/deposits",
                Map.of("amountMinorUnits", 1_000, "currency", "USD"));
        String paymentId = (String) withKey(alice, "/api/v1/payments",
                Map.of("sourceAccountId", aliceWallet, "destinationAccountId", bobShop,
                        "amountMinorUnits", 500, "currency", "USD")).getBody().get("paymentId");

        assertThat(api.get(alice.token(), "/api/v1/payments/" + paymentId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get(bob.token(), "/api/v1/payments/" + paymentId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get(mallory.token(), "/api/v1/payments/" + paymentId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
