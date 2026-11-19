package com.ledgerflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the double-entry invariants are enforced by PostgreSQL itself,
 * independent of any application code:
 *
 *  1. a transaction whose ledger entries do not sum to zero cannot COMMIT
 *  2. a balanced transaction commits normally
 *  3. ledger entries are append-only (UPDATE/DELETE rejected)
 *  4. account balances cannot go below their floor (CHECK constraint)
 */
@IntegrationTest
class SchemaInvariantsIT {

    private static final String SYSTEM_CASH = "00000000-0000-7000-8000-000000000001";
    // Raw entries in these tests bypass balances on purpose. They post to
    // the fees account, not suspense: suspense holds real money on hold and
    // its balance is checked against open holds by TransferHoldIT.
    private static final String SYSTEM_FEES = "00000000-0000-7000-8000-000000000002";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate txTemplate;

    @Test
    void unbalancedLedgerTransactionIsRejectedAtCommit() {
        UUID txnId = UUID.randomUUID();

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            OffsetDateTime createdAt = insertTransaction(txnId, 100);
            insertEntry(txnId, createdAt, SYSTEM_CASH, 100); // single-sided: sum = 100
        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("unbalanced ledger transaction");

        Integer committed = jdbc.sql("SELECT count(*) FROM transactions WHERE id = :id")
                .param("id", txnId).query(Integer.class).single();
        assertThat(committed).isZero();
    }

    @Test
    void balancedLedgerTransactionCommits() {
        UUID txnId = UUID.randomUUID();

        txTemplate.executeWithoutResult(status -> {
            OffsetDateTime createdAt = insertTransaction(txnId, 250);
            insertEntry(txnId, createdAt, SYSTEM_CASH, -250);
            insertEntry(txnId, createdAt, SYSTEM_FEES, 250);
        });

        Long sum = jdbc.sql("SELECT COALESCE(sum(amount), -1) FROM ledger_entries WHERE transaction_id = :id")
                .param("id", txnId).query(Long.class).single();
        assertThat(sum).isZero();
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        UUID txnId = UUID.randomUUID();
        txTemplate.executeWithoutResult(status -> {
            OffsetDateTime createdAt = insertTransaction(txnId, 10);
            insertEntry(txnId, createdAt, SYSTEM_CASH, -10);
            insertEntry(txnId, createdAt, SYSTEM_FEES, 10);
        });

        assertThatThrownBy(() ->
                jdbc.sql("UPDATE ledger_entries SET amount = 999 WHERE transaction_id = :id")
                        .param("id", txnId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM ledger_entries WHERE transaction_id = :id")
                        .param("id", txnId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void platformHasOneAccountOfEachKindPerCurrency() {
        // A second USD suspense account would split held money in two and
        // leave the lookup by (type, currency) ambiguous.
        assertThatThrownBy(() ->
                jdbc.sql("""
                        INSERT INTO accounts (id, user_id, type, currency, status, name)
                        VALUES (:id, NULL, 'SYSTEM_SUSPENSE', 'USD', 'ACTIVE', 'impostor')
                        """).param("id", UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ux_accounts_one_system_account_per_currency");
    }

    @Test
    void balanceCannotDropBelowFloor() {
        UUID accountId = UUID.randomUUID();
        // A throwaway platform account in a currency nothing else uses, so
        // it cannot collide with the real USD ones. Each run needs its own.
        String currency = jdbc.sql("""
                        SELECT c FROM (VALUES ('XTS'), ('XXA'), ('XXB'), ('XXC'), ('XXD')) AS v(c)
                        WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE type = 'SYSTEM_SUSPENSE' AND currency = c)
                        LIMIT 1
                        """).query(String.class).single();
        jdbc.sql("""
                INSERT INTO accounts (id, user_id, type, currency, status, name)
                VALUES (:id, NULL, 'SYSTEM_SUSPENSE', :currency, 'ACTIVE', 'floor test')
                """).param("id", accountId).param("currency", currency).update();
        jdbc.sql("INSERT INTO account_balances (account_id, balance, min_balance) VALUES (:id, 50, 0)")
                .param("id", accountId).update();

        assertThatThrownBy(() ->
                jdbc.sql("UPDATE account_balances SET balance = balance - 100 WHERE account_id = :id")
                        .param("id", accountId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_balance_floor");
    }

    private OffsetDateTime insertTransaction(UUID txnId, long amount) {
        return jdbc.sql("""
                        INSERT INTO transactions (id, type, status, amount, currency, description)
                        VALUES (:id, 'DEPOSIT', 'COMPLETED', :amount, 'USD', 'invariant test')
                        RETURNING created_at
                        """)
                .param("id", txnId)
                .param("amount", amount)
                .query(OffsetDateTime.class)
                .single();
    }

    private void insertEntry(UUID txnId, OffsetDateTime createdAt, String accountId, long amount) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (id, transaction_id, created_at, account_id, amount, currency)
                        VALUES (:id, :txnId, :createdAt, :accountId, :amount, 'USD')
                        """)
                .param("id", UUID.randomUUID())
                .param("txnId", txnId)
                .param("createdAt", createdAt)
                .param("accountId", UUID.fromString(accountId))
                .param("amount", amount)
                .update();
    }
}
