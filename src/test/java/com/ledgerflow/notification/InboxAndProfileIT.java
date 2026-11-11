package com.ledgerflow.notification;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The parts of the product a person sees about themselves: the inbox,
 * their profile and password, and their own activity trail.
 */
@IntegrationTest
class InboxAndProfileIT {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    TestRestTemplate rest;

    ApiTestClient api;
    Money money;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
        money = new Money(api);
    }

    private Map<?, ?> inbox(ApiTestClient.Session who) {
        return api.get(who.token(), "/api/v1/notifications").getBody();
    }

    @Test
    void receivingMoneyLandsInTheInboxAndCanBeMarkedRead() {
        ApiTestClient.Session alice = api.registerNamed("Alice Stone");
        ApiTestClient.Session bob = api.registerNamed("Bob Reyes");
        String aliceWallet = money.openWallet(alice, "Everyday");
        String aliceSavings = money.openWallet(alice, "Savings");
        String bobWallet = money.openWallet(bob, "Bob wallet");
        money.deposit(alice, aliceWallet, 10_000);

        money.transfer(alice, aliceWallet, bobWallet, 1_250, "thanks");
        money.transfer(alice, aliceWallet, bobWallet, 300, "coffee");
        // Moving money between her own accounts is not news.
        money.transfer(alice, aliceWallet, aliceSavings, 2_000, "saving");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(((Number) inbox(bob).get("unreadCount")).longValue()).isEqualTo(2));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) inbox(bob).get("items");
        assertThat(items).extracting(n -> n.get("title")).containsOnly("Money received");
        assertThat(items).extracting(n -> n.get("body"))
                .containsExactlyInAnyOrder("Alice Stone sent you $12.50.", "Alice Stone sent you $3.00.");
        assertThat(items).allSatisfy(n -> assertThat(n.get("read")).isEqualTo(false));
        // Alice did the sending, so she is not notified about her own transfers.
        assertThat(((Number) inbox(alice).get("unreadCount")).longValue()).isZero();

        // Read one, then the rest.
        String first = (String) items.getFirst().get("id");
        assertThat(api.post(bob.token(), "/api/v1/notifications/" + first + "/read", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(((Number) inbox(bob).get("unreadCount")).longValue()).isEqualTo(1);
        // Someone else's notification cannot be marked.
        assertThat(api.post(alice.token(), "/api/v1/notifications/" + first + "/read", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(api.post(bob.token(), "/api/v1/notifications/read-all", Map.of()).getBody().get("updated"))
                .isEqualTo(1);
        assertThat(((Number) inbox(bob).get("unreadCount")).longValue()).isZero();
    }

    @Test
    void profileCanBeReadRenamedAndThePasswordChanged() {
        String email = "user-" + UUID.randomUUID() + "@test.ledgerflow.io";
        ApiTestClient.Session session = api.registerAndLogin(email, PASSWORD, "Dana Whitfield");

        Map<?, ?> me = api.get(session.token(), "/api/v1/me").getBody();
        assertThat(me.get("email")).isEqualTo(email);
        assertThat(me.get("fullName")).isEqualTo("Dana Whitfield");
        assertThat(me.get("role")).isEqualTo("USER");
        assertThat(me.containsKey("passwordHash")).isFalse();
        assertThat(api.get(null, "/api/v1/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(api.patch(session.token(), "/api/v1/me", Map.of("fullName", "Dana W. Reed"))
                .getBody().get("fullName")).isEqualTo("Dana W. Reed");

        // The current password is required, and the new one must be long enough and different.
        String newPassword = "a-much-better-passphrase-2";
        assertThat(api.post(session.token(), "/api/v1/me/password",
                Map.of("currentPassword", "not-my-password", "newPassword", newPassword))
                .getBody().get("code")).isEqualTo("WRONG_PASSWORD");
        assertThat(api.post(session.token(), "/api/v1/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "short"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.post(session.token(), "/api/v1/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD))
                .getBody().get("code")).isEqualTo("PASSWORD_UNCHANGED");

        assertThat(api.post(session.token(), "/api/v1/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", newPassword))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The old password no longer signs in; the new one does, and login says who you are.
        assertThat(api.post(null, "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> login = api.post(null, "/api/v1/auth/login",
                Map.of("email", email, "password", newPassword));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("fullName")).isEqualTo("Dana W. Reed");
    }

    @Test
    void activityListsWhatThePersonDidNewestFirst() {
        ApiTestClient.Session alice = api.registerNamed("Alice Stone");
        ApiTestClient.Session bob = api.registerNamed("Bob Reyes");
        String wallet = money.openWallet(alice, "Everyday");
        money.deposit(alice, wallet, 5_000);
        api.post(alice.token(), "/api/v1/accounts/" + wallet + "/freeze", Map.of());
        api.post(alice.token(), "/api/v1/accounts/" + wallet + "/unfreeze", Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) api.get(alice.token(), "/api/v1/activity")
                .getBody().get("items");
        assertThat(items).extracting(a -> a.get("summary")).containsExactly(
                "Unfroze an account", "Froze an account", "Added money", "Opened an account",
                "Signed in", "Created your LedgerFlow profile");
        assertThat(items).extracting(a -> a.get("category")).containsExactly(
                "ACCOUNT", "ACCOUNT", "MONEY", "ACCOUNT", "SECURITY", "SECURITY");
        // Internal before/after state is not part of what is shown.
        assertThat(items.getFirst()).doesNotContainKeys("previousState", "newState", "correlationId");

        // Pages do not overlap, and nobody sees anyone else's trail.
        Set<Object> seen = new HashSet<>();
        String cursor = null;
        do {
            Map<?, ?> page = api.get(alice.token(),
                    "/api/v1/activity?limit=4" + (cursor == null ? "" : "&cursor=" + cursor)).getBody();
            for (Object item : (List<?>) page.get("items")) {
                assertThat(seen.add(((Map<?, ?>) item).get("id"))).isTrue();
            }
            cursor = (String) page.get("nextCursor");
        } while (cursor != null);
        assertThat(seen).hasSize(6);

        List<?> bobs = (List<?>) api.get(bob.token(), "/api/v1/activity").getBody().get("items");
        assertThat(bobs).hasSize(2);
        assertThat(api.get(alice.token(), "/api/v1/activity?cursor=not-a-cursor").getBody().get("code"))
                .isEqualTo("INVALID_CURSOR");
    }
}
