package com.ledgerflow.identity.persistence;

import java.util.Optional;
import java.util.UUID;

import com.ledgerflow.identity.domain.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(User user) {
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, role, status)
                        VALUES (:id, :email, :passwordHash, :fullName, :role, :status)
                        """)
                .param("id", user.id())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("fullName", user.fullName())
                .param("role", user.role())
                .param("status", user.status())
                .update();
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT * FROM users WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(this::map)
                .optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    private User map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
