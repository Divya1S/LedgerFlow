package com.ledgerflow.identity;

import java.util.Map;
import java.util.UUID;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AuthFlowIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    ApiTestClient api;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
    }

    @Test
    void registerLoginAndAccessProtectedEndpoint() {
        ApiTestClient.Session session = api.registerAndLogin();

        ResponseEntity<java.util.List> accounts = api.getList(session.token(), "/api/v1/accounts");
        assertThat(accounts.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void duplicateEmailIsConflict() {
        String email = "dup-" + UUID.randomUUID() + "@test.ledgerflow.io";
        api.registerAndLogin(email, "correct-horse-battery-staple");

        ResponseEntity<Map> second = api.post(null, "/api/v1/auth/register",
                Map.of("email", email.toUpperCase(), "password", "correct-horse-battery-staple",
                        "fullName", "Dup"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void wrongPasswordIsUnauthorizedWithoutRevealingWhichFieldWasWrong() {
        ApiTestClient.Session session = api.registerAndLogin();

        ResponseEntity<Map> login = api.post(null, "/api/v1/auth/login",
                Map.of("email", session.email(), "password", "wrong-password-entirely"));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody().get("code")).isEqualTo("BAD_CREDENTIALS");

        ResponseEntity<Map> unknown = api.post(null, "/api/v1/auth/login",
                Map.of("email", "nobody-" + UUID.randomUUID() + "@test.ledgerflow.io",
                        "password", "wrong-password-entirely"));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getBody().get("code")).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    void protectedEndpointsRejectMissingAndGarbageTokens() {
        assertThat(api.get(null, "/api/v1/accounts").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("not-a-jwt", "/api/v1/accounts").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        ApiTestClient.Session session = api.registerAndLogin();

        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                .param("id", session.userId()).query(String.class).single();
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse-battery-staple");
    }

    @Test
    void weakPasswordsAreRejected() {
        ResponseEntity<Map> response = api.post(null, "/api/v1/auth/register",
                Map.of("email", "weak-" + UUID.randomUUID() + "@test.ledgerflow.io",
                        "password", "short", "fullName", "Weak"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void sqlInjectionAttemptsAreStoredAsLiterals() {
        String email = "inject-" + UUID.randomUUID() + "@test.ledgerflow.io";
        String maliciousName = "Robert'); DROP TABLE users;--";
        ResponseEntity<Map> response = api.post(null, "/api/v1/auth/register",
                Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", maliciousName));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Parameterized queries mean the payload is inert data, and the table
        // obviously still exists.
        String stored = jdbc.sql("SELECT full_name FROM users WHERE lower(email) = lower(:email)")
                .param("email", email).query(String.class).single();
        assertThat(stored).isEqualTo(maliciousName);
    }
}
