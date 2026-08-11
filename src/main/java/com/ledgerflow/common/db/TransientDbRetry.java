package com.ledgerflow.common.db;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Retries a whole database transaction on transient serialization errors:
 * 40001 (serialization_failure) and 40P01 (deadlock_detected). PostgreSQL
 * resolves deadlocks by killing one participant; the correct response is to
 * rerun that transaction from the start, which is why this wrapper must sit
 * OUTSIDE the @Transactional boundary.
 */
@Component
public class TransientDbRetry {

    private static final Logger log = LoggerFactory.getLogger(TransientDbRetry.class);
    private static final int MAX_ATTEMPTS = 3;

    public <T> T execute(Supplier<T> transactionalOperation) {
        DataAccessException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionalOperation.get();
            } catch (DataAccessException e) {
                if (!isTransient(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                long backoffMillis = (1L << attempt) * 25 + ThreadLocalRandom.current().nextLong(25);
                log.warn("Transient database error (attempt {}/{}), retrying in {}ms: {}",
                        attempt, MAX_ATTEMPTS, backoffMillis, rootSqlState(e));
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private boolean isTransient(DataAccessException e) {
        String state = rootSqlState(e);
        return "40001".equals(state) || "40P01".equals(state);
    }

    private String rootSqlState(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
