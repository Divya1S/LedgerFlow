package com.ledgerflow.account.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountSummary;
import com.ledgerflow.account.domain.Balance;
import com.ledgerflow.account.domain.Payee;
import com.ledgerflow.common.db.PartyLabel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account, long minBalance) {
        jdbc.sql("""
                        INSERT INTO accounts (id, user_id, type, currency, status, name, category)
                        VALUES (:id, :userId, :type, :currency, :status, :name, :category)
                        """)
                .param("id", account.id())
                .param("userId", account.userId())
                .param("type", account.type())
                .param("currency", account.currency())
                .param("status", account.status())
                .param("name", account.name())
                .param("category", account.category())
                .update();
        jdbc.sql("""
                        INSERT INTO account_balances (account_id, balance, min_balance)
                        VALUES (:accountId, 0, :minBalance)
                        """)
                .param("accountId", account.id())
                .param("minBalance", minBalance)
                .update();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT * FROM accounts WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<Account> findByUserId(UUID userId) {
        return jdbc.sql("SELECT * FROM accounts WHERE user_id = :userId ORDER BY created_at")
                .param("userId", userId)
                .query(this::map)
                .list();
    }

    /** A user's accounts with balances, one query instead of one per account. */
    public List<AccountSummary> findSummariesByUserId(UUID userId) {
        return jdbc.sql("""
                        SELECT a.*, b.balance
                        FROM accounts a
                        JOIN account_balances b ON b.account_id = a.id
                        WHERE a.user_id = :userId
                        ORDER BY a.created_at, a.id
                        """)
                .param("userId", userId)
                .query((rs, n) -> new AccountSummary(map(rs, n), rs.getLong("balance")))
                .list();
    }

    public Optional<AccountSummary> findSummary(UUID accountId) {
        return jdbc.sql("""
                        SELECT a.*, b.balance
                        FROM accounts a
                        JOIN account_balances b ON b.account_id = a.id
                        WHERE a.id = :accountId
                        """)
                .param("accountId", accountId)
                .query((rs, n) -> new AccountSummary(map(rs, n), rs.getLong("balance")))
                .optional();
    }

    public Optional<Balance> findBalance(UUID accountId) {
        return jdbc.sql("""
                        SELECT b.account_id, b.balance, a.currency, b.updated_at
                        FROM account_balances b
                        JOIN accounts a ON a.id = b.account_id
                        WHERE b.account_id = :accountId
                        """)
                .param("accountId", accountId)
                .query((rs, n) -> new Balance(
                        rs.getObject("account_id", UUID.class),
                        rs.getLong("balance"),
                        rs.getString("currency"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();
    }

    /**
     * The public face of a user-facing account, as the viewer should see
     * it. Platform accounts are never payees, so they are not found here.
     */
    public Optional<Payee> findPayee(UUID accountId, UUID viewerUserId) {
        return jdbc.sql("""
                        SELECT a.id, a.type, a.status, a.currency, a.category,
                               a.user_id = :viewer AS own,
                               %s AS display_name
                        FROM accounts a
                        LEFT JOIN users u ON u.id = a.user_id
                        WHERE a.id = :accountId AND a.user_id IS NOT NULL
                        """.formatted(PartyLabel.sql("a", "u", "viewer")))
                .param("accountId", accountId)
                .param("viewer", viewerUserId)
                .query((rs, n) -> new Payee(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getString("currency"),
                        rs.getString("display_name"),
                        rs.getString("category"),
                        rs.getBoolean("own")))
                .optional();
    }

    public int updateStatus(UUID id, String status) {
        return jdbc.sql("UPDATE accounts SET status = :status WHERE id = :id")
                .param("id", id)
                .param("status", status)
                .update();
    }

    public int updateName(UUID id, String name) {
        return jdbc.sql("UPDATE accounts SET name = :name WHERE id = :id")
                .param("id", id)
                .param("name", name)
                .update();
    }

    private Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("type"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
