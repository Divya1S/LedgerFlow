package com.ledgerflow.identity.domain;

import java.util.UUID;

import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.identity.persistence.UserRepository;
import com.ledgerflow.identity.security.JwtService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogger audit;

    public IdentityService(UserRepository users, PasswordEncoder passwordEncoder,
                           JwtService jwtService, AuditLogger audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    @Transactional
    public User register(String email, String rawPassword, String fullName) {
        User user = new User(Uuid7.generate(), email, passwordEncoder.encode(rawPassword),
                fullName, "USER", "ACTIVE", null);
        try {
            users.insert(user);
        } catch (DuplicateKeyException e) {
            // The unique index on lower(email) is the authority; the service
            // just translates the violation. No pre-check SELECT: it would
            // race with concurrent registrations anyway.
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        audit.record(user.id(), "USER_REGISTERED", "user", user.id().toString(),
                null, "{\"email\":\"" + email + "\"}", "identity", null);
        return user;
    }

    public AuthResult login(String email, String rawPassword) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized("BAD_CREDENTIALS", "Invalid email or password"));
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw ApiException.unauthorized("BAD_CREDENTIALS", "Invalid email or password");
        }
        if (!"ACTIVE".equals(user.status())) {
            throw ApiException.forbidden("USER_INACTIVE", "This user account is not active");
        }
        audit.record(user.id(), "USER_LOGIN", "user", user.id().toString(),
                null, null, "identity", null);
        return new AuthResult(user, jwtService.issueToken(user));
    }

    public User requireUser(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> ApiException.unauthorized("UNKNOWN_USER", "Token subject no longer exists"));
    }

    public record AuthResult(User user, String token) {
    }
}
