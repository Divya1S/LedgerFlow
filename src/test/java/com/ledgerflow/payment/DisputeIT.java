package com.ledgerflow.payment;

import java.time.Duration;
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
import static org.awaitility.Awaitility.await;

/**
 * The dispute flow end to end: who may open one, who may decide it, and
 * that a decision for the customer moves the money in the same breath.
 */
@IntegrationTest
class DisputeIT {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;
    Money money;

    ApiTestClient.Session alice;
    ApiTestClient.Session bob;
    String aliceWallet;
    String bobShop;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
        money = new Money(api);
        alice = api.registerNamed("Alice Stone");
        bob = api.registerNamed("Bob Reyes");
        aliceWallet = money.openWallet(alice, "Everyday");
        bobShop = money.openShop(bob, "Reyes Electronics", "SHOPPING");
        money.deposit(alice, aliceWallet, 10_000);
    }

    private String payShop(long amount) {
        ResponseEntity<Map> payment = money.pay(alice, aliceWallet, bobShop, amount, "headphones");
        assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) payment.getBody().get("paymentId");
    }

    private ResponseEntity<Map> open(ApiTestClient.Session who, String paymentId, String reason) {
        return api.post(who.token(), "/api/v1/payments/" + paymentId + "/disputes", Map.of("reason", reason));
    }

    private ResponseEntity<Map> decide(ApiTestClient.Session who, String disputeId, String outcome, String key) {
        return api.post(who.token(), "/api/v1/disputes/" + disputeId + "/decision",
                Map.of("outcome", outcome, "note", "note from " + who.email()), key);
    }

    /** A platform admin: role set in the database, then a fresh login so the token carries it. */
    private ApiTestClient.Session admin() {
        ApiTestClient.Session user = api.registerNamed("Platform Admin");
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE id = :id").param("id", user.userId()).update();
        return api.login(user.email(), PASSWORD);
    }

    @Test
    void declinedDisputeGoesToThePlatformWhichRefundsTheCustomer() {
        String paymentId = payShop(5_000);
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(5_000);
        assertThat(money.balance(bob, bobShop)).isEqualTo(4_950);

        // Only the payer can dispute. Everyone else is told the payment does not exist.
        ApiTestClient.Session mallory = api.registerAndLogin();
        assertThat(open(mallory, paymentId, "mine now").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(open(bob, paymentId, "disputing my own sale").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> opened = open(alice, paymentId, "Item never arrived");
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String disputeId = (String) opened.getBody().get("disputeId");
        assertThat(opened.getBody().get("status")).isEqualTo("OPEN");
        assertThat(opened.getBody().get("role")).isEqualTo("CUSTOMER");
        assertThat(opened.getBody().get("canDecide")).isEqualTo(false);

        // One open dispute per payment.
        assertThat(open(alice, paymentId, "again").getBody().get("code")).isEqualTo("DISPUTE_ALREADY_OPEN");

        // The merchant sees it and can act; a stranger sees nothing.
        Map<?, ?> forBob = (Map<?, ?>) api.getList(bob.token(), "/api/v1/disputes").getBody().getFirst();
        assertThat(forBob.get("role")).isEqualTo("MERCHANT");
        assertThat(forBob.get("canDecide")).isEqualTo(true);
        assertThat(forBob.get("payerName")).isEqualTo("Alice Stone");
        assertThat(api.getList(mallory.token(), "/api/v1/disputes").getBody()).isEmpty();
        // The customer cannot decide their own dispute.
        assertThat(decide(alice, disputeId, "REFUND", Money.key()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A decision moves money, so it needs an idempotency key.
        assertThat(decide(bob, disputeId, "DECLINE", null).getBody().get("code"))
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        // The merchant declines: no money moves, the platform takes over.
        ResponseEntity<Map> declined = decide(bob, disputeId, "DECLINE", Money.key());
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(declined.getBody().get("status")).isEqualTo("UNDER_REVIEW");
        assertThat((String) declined.getBody().get("merchantResponse")).startsWith("note from");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(5_000);
        // A merchant cannot close a dispute in its own favour by declining twice.
        assertThat(decide(bob, disputeId, "DECLINE", Money.key()).getBody().get("code"))
                .isEqualTo("DISPUTE_UNDER_REVIEW");

        // The platform decides for the customer: the refund and the status change commit together.
        ApiTestClient.Session admin = admin();
        assertThat(api.getList(admin.token(), "/api/v1/disputes").getBody())
                .anySatisfy(d -> assertThat(((Map<?, ?>) d).get("disputeId")).isEqualTo(disputeId));
        String key = Money.key();
        ResponseEntity<Map> resolved = decide(admin, disputeId, "REFUND", key);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("status")).isEqualTo("RESOLVED_CUSTOMER");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(10_000);
        assertThat(money.balance(bob, bobShop)).isZero();

        // The same request again replays the stored answer. No second refund.
        ResponseEntity<Map> replay = decide(admin, disputeId, "REFUND", key);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(10_000);
        // And a new decision on a closed dispute is refused.
        assertThat(decide(admin, disputeId, "DECLINE", Money.key()).getBody().get("code"))
                .isEqualTo("DISPUTE_ALREADY_RESOLVED");

        // The payment list tells the same story to both sides.
        Map<?, ?> sent = firstPayment(alice, "PAYER");
        assertThat(sent.get("status")).isEqualTo("REFUNDED");
        assertThat(sent.get("disputeStatus")).isEqualTo("RESOLVED_CUSTOMER");
        assertThat(sent.get("merchantName")).isEqualTo("Reyes Electronics");
        assertThat(sent.get("fraudVerdict")).isNull(); // never shown to the payer
        Map<?, ?> received = firstPayment(bob, "MERCHANT");
        assertThat(received.get("payerName")).isEqualTo("Alice Stone");
        assertThat(received.get("description")).isEqualTo("headphones");

        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
        LedgerAssertions.assertPerTransactionBalanced(jdbc);
        LedgerAssertions.assertBalancesMatchLedger(jdbc,
                List.of(UUID.fromString(aliceWallet), UUID.fromString(bobShop)));

        // Both sides were told along the way.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(notificationTypes(bob.userId())).contains("PAYMENT_RECEIVED", "DISPUTE_OPENED");
            assertThat(notificationTypes(alice.userId())).contains("DISPUTE_UPDATED", "REFUND_COMPLETED");
        });
    }

    @Test
    void merchantCanSettleADisputeByRefundingWhatIsLeft() {
        String paymentId = payShop(4_000);
        // Half was already refunded before the dispute.
        api.post(bob.token(), "/api/v1/payments/" + paymentId + "/refunds",
                Map.of("amountMinorUnits", 2_000), Money.key());
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(8_000);

        String disputeId = (String) open(alice, paymentId, "Wrong colour").getBody().get("disputeId");
        ResponseEntity<Map> settled = decide(bob, disputeId, "REFUND", Money.key());
        assertThat(settled.getBody().get("status")).isEqualTo("RESOLVED_CUSTOMER");

        // Only the remaining half moved; the customer is whole and not over-refunded.
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(10_000);
        assertThat(money.balance(bob, bobShop)).isZero();
        // With the dispute closed, a new one can be opened only on a payment that still holds money.
        assertThat(open(alice, paymentId, "again").getBody().get("code")).isEqualTo("PAYMENT_NOT_DISPUTABLE");
        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
    }

    @Test
    void disputeStaysOpenWhenTheMerchantCannotCoverTheRefund() {
        String paymentId = payShop(3_000);
        money.withdraw(bob, bobShop, 2_970); // the shop spends what it received
        String disputeId = (String) open(alice, paymentId, "Never shipped").getBody().get("disputeId");

        ResponseEntity<Map> refused = decide(bob, disputeId, "REFUND", Money.key());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody().get("code")).isEqualTo("INSUFFICIENT_FUNDS");

        // Nothing half-happened: no refund, and the dispute is still open.
        assertThat(money.balance(alice, aliceWallet)).isEqualTo(7_000);
        String status = jdbc.sql("SELECT status FROM disputes WHERE id = :id")
                .param("id", UUID.fromString(disputeId)).query(String.class).single();
        assertThat(status).isEqualTo("OPEN");
        LedgerAssertions.assertGlobalLedgerBalanced(jdbc);
    }

    @Test
    void merchantSeesTheFraudVerdictInThePaymentsList() {
        payShop(1_500);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(firstPayment(bob, "MERCHANT").get("fraudVerdict")).isEqualTo("APPROVED"));
        assertThat(api.get(bob.token(), "/api/v1/payments?role=OWNER").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private Map<?, ?> firstPayment(ApiTestClient.Session who, String role) {
        List<?> items = (List<?>) api.get(who.token(), "/api/v1/payments?role=" + role).getBody().get("items");
        assertThat(items).isNotEmpty();
        return (Map<?, ?>) items.getFirst();
    }

    private List<String> notificationTypes(UUID userId) {
        return jdbc.sql("SELECT type FROM notifications WHERE user_id = :u").param("u", userId)
                .query(String.class).list();
    }
}
