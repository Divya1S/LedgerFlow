package com.ledgerflow.transfer.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls for scheduled transfers that are due and runs them, one database
 * transaction per schedule so one failure never rolls back another.
 */
@Component
public class ScheduledTransferRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferRunner.class);

    private final ScheduledTransferService service;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final int batchSize;

    public ScheduledTransferRunner(ScheduledTransferService service, TransactionTemplate transaction,
                                   @Value("${ledgerflow.scheduled-transfers.enabled:true}") boolean enabled,
                                   @Value("${ledgerflow.scheduled-transfers.batch-size:50}") int batchSize) {
        this.service = service;
        this.transaction = transaction;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.scheduled-transfers.poll-interval-ms:15000}",
            initialDelayString = "${ledgerflow.scheduled-transfers.poll-interval-ms:15000}")
    void poll() {
        if (enabled) {
            runDue();
        }
    }

    /**
     * Runs every schedule that is due right now, up to the batch size.
     *
     * @return how many were processed
     */
    public int runDue() {
        int processed = 0;
        try {
            while (processed < batchSize
                    && Boolean.TRUE.equals(transaction.execute(status -> service.runNextDue()))) {
                processed++;
            }
        } catch (RuntimeException e) {
            // Database unavailable or similar. Nothing was committed for
            // the schedule in flight, so the next poll picks it up again.
            log.error("scheduled transfer poll failed after {} runs", processed, e);
        }
        return processed;
    }
}
