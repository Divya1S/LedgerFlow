package com.ledgerflow.transfer.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases held transfers when their time comes, one database transaction
 * per hold. This is what makes the release time a promise: nobody has to
 * approve anything, and a restart only delays a release by one poll.
 */
@Component
public class TransferHoldRunner {

    private static final Logger log = LoggerFactory.getLogger(TransferHoldRunner.class);

    private final TransferHoldService service;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final int batchSize;

    public TransferHoldRunner(TransferHoldService service, TransactionTemplate transaction,
                              @Value("${ledgerflow.protection.release-enabled:true}") boolean enabled,
                              @Value("${ledgerflow.protection.release-batch-size:100}") int batchSize) {
        this.service = service;
        this.transaction = transaction;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledgerflow.protection.release-poll-ms:15000}",
            initialDelayString = "${ledgerflow.protection.release-poll-ms:15000}")
    void poll() {
        if (enabled) {
            releaseDue();
        }
    }

    /**
     * Releases every hold that is due right now, up to the batch size.
     *
     * @return how many were processed
     */
    public int releaseDue() {
        int processed = 0;
        try {
            while (processed < batchSize
                    && Boolean.TRUE.equals(transaction.execute(status -> service.releaseNextDue()))) {
                processed++;
            }
        } catch (RuntimeException e) {
            // Nothing was committed for the hold in flight; the next poll picks it up.
            log.error("held transfer poll failed after {} releases", processed, e);
        }
        return processed;
    }
}
