package com.ledgerflow.account.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.Balance;
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
                        INSERT INTO accounts (id, user_id, type, currency, status, name)
                        VALUES (:id, :userId, :type, :currency, :status, :name)
                        """)
                .param("id", account.id())
                .param("userId", account.userId())
                .param("type", account.type())
                .param("currency", account.currency())
                .param("status", account.status())
                .param("name", account.name())
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

    public int updateStatus(UUID id, String status) {
        return jdbc.sql("UPDATE accounts SET status = :status WHERE id = :id")
                .param("id", id)
                .param("status", status)
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
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
