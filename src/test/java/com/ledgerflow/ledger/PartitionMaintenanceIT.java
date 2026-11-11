package com.ledgerflow.ledger;

import com.ledgerflow.ledger.domain.PartitionMaintenance;
import com.ledgerflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partitions for coming months are recreated when they are missing,
 * with the zero-sum trigger attached, and nothing happens when they exist.
 */
@IntegrationTest
class PartitionMaintenanceIT {

    @Autowired
    PartitionMaintenance maintenance;
    @Autowired
    JdbcClient jdbc;

    private String furthestSuffix() {
        return jdbc.sql("""
                        SELECT to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + interval '3 months', 'YYYY_MM')
                        """)
                .query(String.class).single();
    }

    private boolean exists(String table) {
        return jdbc.sql("SELECT to_regclass(:name) IS NOT NULL").param("name", table)
                .query(Boolean.class).single();
    }

    @Test
    void missingFutureMonthIsCreatedWithItsBalanceTrigger() {
        String suffix = furthestSuffix();
        // Simulate a database that has aged past what the first migration
        // created, by removing the furthest month's ledger partition. It is
        // three months out, so no test has written rows into it. (The
        // matching transactions partition is referenced by foreign keys
        // and stays; one missing table is enough to need maintenance.)
        jdbc.sql("DROP TABLE ledger_entries_" + suffix).update();
        assertThat(exists("ledger_entries_" + suffix)).isFalse();

        assertThat(maintenance.ensurePartitions()).isTrue();

        assertThat(exists("transactions_" + suffix)).isTrue();
        assertThat(exists("ledger_entries_" + suffix)).isTrue();
        Long triggers = jdbc.sql("SELECT count(*) FROM pg_trigger WHERE tgname = :name")
                .param("name", "trg_ledger_balanced_" + suffix).query(Long.class).single();
        assertThat(triggers).as("zero-sum trigger on the new ledger partition").isEqualTo(1);

        // With everything in place a second run has nothing to do.
        assertThat(maintenance.ensurePartitions()).isFalse();
    }
}
