package com.ledgerflow.common.security;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Typed view over the authenticated JWT principal.
 */
public record CurrentUser(UUID id, String role) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
