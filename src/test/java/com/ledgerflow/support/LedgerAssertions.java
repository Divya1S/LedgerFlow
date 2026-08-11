package com.ledgerflow.support;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariants every money test asserts after the dust settles.
 */
public final class LedgerAssertions {

    private LedgerAssertions() {
    }

    /** Sum of all ledger entries in the system must always be zero. */
    public static void assertGlobalLedgerBalanced(JdbcClient jdbc) {
        Long sum = jdbc.sql("SELECT COALESCE(sum(amount), 0) FROM ledger_entries")
                .query(Long.class).single();
        assertThat(sum).as("global ledger sum").isZero();
    }

    /** Each account's stored balance must equal the sum of its ledger entries. */
    public static void assertBalancesMatchLedger(JdbcClient jdbc, Collection<UUID> accountIds) {
        List<Map<String, Object>> mismatches = jdbc.sql("""
                        SELECT b.account_id, b.balance,
                               COALESCE(s.ledger_sum, 0) AS ledger_sum
                        FROM account_balances b
                        LEFT JOIN (
                            SELECT account_id, sum(amount) AS ledger_sum
                            FROM ledger_entries
                            GROUP BY account_id
                        ) s ON s.account_id = b.account_id
                        WHERE b.account_id = ANY(:ids)
                          AND b.balance <> COALESCE(s.ledger_sum, 0)
                        """)
                .param("ids", accountIds.toArray(UUID[]::new))
                .query()
                .listOfRows();
        assertThat(mismatches).as("accounts whose balance disagrees with their ledger").isEmpty();
    }

    /** Every transaction's entries must sum to zero (belt to the DB trigger's suspenders). */
    public static void assertPerTransactionBalanced(JdbcClient jdbc) {
        List<Map<String, Object>> unbalanced = jdbc.sql("""
                        SELECT transaction_id, sum(amount) AS entry_sum
                        FROM ledger_entries
                        GROUP BY transaction_id
                        HAVING sum(amount) <> 0
                        """)
                .query()
                .listOfRows();
        assertThat(unbalanced).as("transactions with unbalanced entries").isEmpty();
    }
}
