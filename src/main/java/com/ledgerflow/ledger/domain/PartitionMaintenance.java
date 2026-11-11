package com.ledgerflow.ledger.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the monthly partitions of transactions and ledger_entries ahead of
 * the calendar.
 *
 * The first migration creates partitions for the current month and the
 * next three. Without this job a database that has been running for four
 * months has no partition for today, and every money movement fails with
 * "no partition of relation found for row". There is deliberately no
 * DEFAULT partition to hide that (see V1), so the months must exist.
 *
 * Runs at startup and once a day. Most runs do nothing: the check is one
 * catalog lookup, and partitions are only created (which briefly locks the
 * parent tables) on the day a new month comes into the three-month window.
 * A transaction-scoped advisory lock keeps several app instances starting
 * together from creating the same partition at once.
 */
@Component
public class PartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);
    static final int MONTHS_AHEAD = 3;

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PartitionMaintenance(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensurePartitions();
    }

    @Scheduled(cron = "${ledgerflow.partitions.cron:0 15 0 * * *}", zone = "UTC")
    void daily() {
        ensurePartitions();
    }

    /**
     * Creates any missing partition from this month to MONTHS_AHEAD months out.
     *
     * @return true if partitions had to be created
     */
    public boolean ensurePartitions() {
        try {
            if (covered()) {
                return false;
            }
            transaction.executeWithoutResult(status -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('ledgerflow.partition-maintenance'))")
                        .query(rs -> { });
                if (!covered()) { // another instance may have done it while we waited
                    jdbc.sql("SELECT create_month_partitions(:months)")
                            .param("months", MONTHS_AHEAD)
                            .query(rs -> { });
                    log.info("created ledger partitions through {} months ahead", MONTHS_AHEAD);
                }
            });
            return true;
        } catch (RuntimeException e) {
            // Loud, not fatal: today's partition almost always exists already,
            // and the next daily run tries again.
            log.error("partition maintenance failed; money movements will fail once coverage runs out", e);
            return false;
        }
    }

    /** True when every month in the window already has both partitions. */
    private boolean covered() {
        return jdbc.sql("""
                        SELECT bool_and(
                                   to_regclass('transactions_' || to_char(m, 'YYYY_MM')) IS NOT NULL
                               AND to_regclass('ledger_entries_' || to_char(m, 'YYYY_MM')) IS NOT NULL)
                        FROM generate_series(
                                 date_trunc('month', now() AT TIME ZONE 'UTC'),
                                 date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => :months),
                                 interval '1 month') AS m
                        """)
                .param("months", MONTHS_AHEAD)
                .query(Boolean.class)
                .single();
    }
}
