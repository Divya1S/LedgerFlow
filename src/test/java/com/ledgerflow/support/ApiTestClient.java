package com.ledgerflow.support;

import java.util.Map;
import java.util.UUID;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Thin helper for driving the real HTTP API in integration tests.
 */
public class ApiTestClient {

    private final TestRestTemplate rest;

    public ApiTestClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    public record Session(UUID userId, String token, String email) {
    }

    /** Registers a fresh user with a unique email and logs in. */
    public Session registerAndLogin() {
        String email = "user-" + UUID.randomUUID() + "@test.ledgerflow.io";
        return registerAndLogin(email, "correct-horse-battery-staple");
    }

    /** Registers a fresh user with a chosen display name and logs in. */
    public Session registerNamed(String fullName) {
        String email = "user-" + UUID.randomUUID() + "@test.ledgerflow.io";
        return registerAndLogin(email, "correct-horse-battery-staple", fullName);
    }

    public Session registerAndLogin(String email, String password) {
        return registerAndLogin(email, password, "Test User");
    }

    /** Logs in again, for a fresh token after the user's role changed. */
    @SuppressWarnings("unchecked")
    public Session login(String email, String password) {
        ResponseEntity<Map> login = post(null, "/api/v1/auth/login",
                Map.of("email", email, "password", password));
        if (!login.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("login failed: " + login.getStatusCode());
        }
        return new Session(
                UUID.fromString((String) login.getBody().get("userId")),
                (String) login.getBody().get("accessToken"),
                email);
    }

    @SuppressWarnings("unchecked")
    public Session registerAndLogin(String email, String password, String fullName) {
        ResponseEntity<Map> register = post(null, "/api/v1/auth/register",
                Map.of("email", email, "password", password, "fullName", fullName));
        if (!register.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("register failed: " + register.getStatusCode() + " " + register.getBody());
        }
        ResponseEntity<Map> login = post(null, "/api/v1/auth/login",
                Map.of("email", email, "password", password));
        if (!login.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("login failed: " + login.getStatusCode());
        }
        return new Session(
                UUID.fromString((String) login.getBody().get("userId")),
                (String) login.getBody().get("accessToken"),
                email);
    }

    public ResponseEntity<Map> post(String token, String path, Object body) {
        return exchange(token, HttpMethod.POST, path, body, null);
    }

    public ResponseEntity<Map> post(String token, String path, Object body, String idempotencyKey) {
        return exchange(token, HttpMethod.POST, path, body, idempotencyKey);
    }

    public ResponseEntity<Map> get(String token, String path) {
        return exchange(token, HttpMethod.GET, path, null, null);
    }

    /** For endpoints returning JSON arrays. */
    public ResponseEntity<java.util.List> getList(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), java.util.List.class);
    }

    public ResponseEntity<Map> patch(String token, String path, Object body) {
        return exchange(token, HttpMethod.PATCH, path, body, null);
    }

    /** For endpoints that do not return JSON (file downloads). */
    public ResponseEntity<String> getText(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    public ResponseEntity<Map> delete(String token, String path) {
        return exchange(token, HttpMethod.DELETE, path, null, null);
    }

    private ResponseEntity<Map> exchange(String token, HttpMethod method, String path,
                                         Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
