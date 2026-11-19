package com.ledgerflow.transfer.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.outbox.OutboxWriter;
import com.ledgerflow.transfer.persistence.ScheduledTransferRepository;
import com.ledgerflow.transfer.persistence.ScheduledTransferRepository.ScheduleRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfers that run later: once on a date, or every week or month.
 *
 * Running one is a normal transfer, through the same code path and the
 * same checks as a transfer made by hand. What this class adds is the
 * guarantee that each occurrence runs exactly once:
 *
 *  - The runner locks the schedule row, makes the transfer and moves the
 *    schedule to its next date in ONE database transaction. A crash before
 *    the commit undoes all three; after it, the schedule already points at
 *    the next date. There is no window where money moved but the schedule
 *    still looks due.
 *  - The row lock is taken with SKIP LOCKED, so several app instances can
 *    poll at once without running the same schedule twice.
 *
 * A transfer that is refused (not enough money, frozen account) is not
 * retried in a loop. The refusal is recorded on the schedule, the owner is
 * told, and a repeating schedule simply waits for its next date.
 */
@Service
public class ScheduledTransferService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferService.class);
    private static final int MAX_DAYS_AHEAD = 366;

    private final ScheduledTransferRepository schedules;
    private final TransferService transfers;
    private final AccountService accounts;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final TransactionTemplate savepoint;

    public ScheduledTransferService(ScheduledTransferRepository schedules, TransferService transfers,
                                    AccountService accounts, OutboxWriter outbox, AuditLogger audit,
                                    PlatformTransactionManager transactionManager) {
        this.schedules = schedules;
        this.transfers = transfers;
        this.accounts = accounts;
        this.outbox = outbox;
        this.audit = audit;
        // NESTED = a savepoint inside the runner's transaction. A refused
        // transfer rolls back to the savepoint only, so the schedule row
        // update that records the refusal can still commit.
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public record ScheduleView(UUID id, UUID sourceAccountId, UUID destinationAccountId,
                               String sourceLabel, String destinationLabel,
                               long amountMinorUnits, String currency, String description,
                               String frequency, String status, OffsetDateTime nextRunAt,
                               int runsCompleted, OffsetDateTime lastRunAt, String lastOutcome,
                               OffsetDateTime createdAt) {
        static ScheduleView from(ScheduleRow s) {
            return new ScheduleView(s.id(), s.sourceAccountId(), s.destinationAccountId(),
                    s.sourceLabel(), s.destinationLabel(), s.amount(), s.currency(), s.description(),
                    s.frequency(), s.status(), s.nextRunAt(), s.runsCompleted(), s.lastRunAt(),
                    s.lastOutcome(), s.createdAt());
        }
    }

    @Transactional
    public ScheduleView create(UUID userId, UUID sourceAccountId, UUID destinationAccountId,
                               long amount, String currency, String description,
                               String frequency, LocalDate startDate, ZoneId zone) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Source and destination must differ");
        }
        Account source = accounts.requireOwnedAccount(sourceAccountId, userId, false);
        Account destination = accounts.find(destinationAccountId)
                .filter(a -> a.userId() != null) // platform accounts are not valid destinations
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Destination account not found"));
        if ("CLOSED".equals(source.status()) || "CLOSED".equals(destination.status())) {
            throw ApiException.conflict("ACCOUNT_NOT_ACTIVE", "A closed account cannot be part of a schedule");
        }
        if (!source.currency().equals(currency) || !destination.currency().equals(currency)) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Both accounts must hold " + currency);
        }
        LocalDate today = LocalDate.now(zone);
        if (startDate.isBefore(today)) {
            throw ApiException.unprocessable("START_IN_PAST", "The first transfer cannot be in the past");
        }
        if (startDate.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw ApiException.unprocessable("START_TOO_FAR", "The first transfer must be within a year");
        }

        UUID id = Uuid7.generate();
        // A schedule for today is due right away; later dates run at the
        // start of that day in the owner's time zone.
        OffsetDateTime startAt = startDate.equals(today)
                ? OffsetDateTime.now()
                : startDate.atStartOfDay(zone).toOffsetDateTime();
        String cleanDescription = description == null || description.isBlank() ? null : description.strip();
        schedules.insert(id, userId, sourceAccountId, destinationAccountId, amount, currency,
                cleanDescription, frequency, startAt);
        audit.recordChange(userId, "SCHEDULE_CREATED", "scheduled_transfer", id.toString(), null,
                Map.of("amount", amount, "currency", currency, "frequency", frequency,
                        "startDate", startDate.toString()), "transfer", null);
        return ScheduleView.from(schedules.find(id, userId).orElseThrow());
    }

    public List<ScheduleView> list(UUID userId) {
        return schedules.listForUser(userId).stream().map(ScheduleView::from).toList();
    }

    @Transactional
    public ScheduleView cancel(UUID userId, UUID scheduleId) {
        ScheduleRow schedule = lockOwned(userId, scheduleId);
        if ("ACTIVE".equals(schedule.status()) || "PAUSED".equals(schedule.status())) {
            schedules.updateStatus(scheduleId, "CANCELLED", schedule.nextOccurrence(), null);
            audit.record(userId, "SCHEDULE_CANCELLED", "scheduled_transfer", scheduleId.toString(),
                    "{\"status\":\"" + schedule.status() + "\"}", "{\"status\":\"CANCELLED\"}", "transfer", null);
        }
        return ScheduleView.from(schedules.find(scheduleId, userId).orElseThrow());
    }

    @Transactional
    public ScheduleView pause(UUID userId, UUID scheduleId) {
        ScheduleRow schedule = lockOwned(userId, scheduleId);
        if ("PAUSED".equals(schedule.status())) {
            return ScheduleView.from(schedule);
        }
        if (!"ACTIVE".equals(schedule.status())) {
            throw ApiException.conflict("SCHEDULE_NOT_ACTIVE", "Only an active schedule can be paused");
        }
        schedules.updateStatus(scheduleId, "PAUSED", schedule.nextOccurrence(), schedule.nextRunAt());
        audit.record(userId, "SCHEDULE_PAUSED", "scheduled_transfer", scheduleId.toString(),
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"PAUSED\"}", "transfer", null);
        return ScheduleView.from(schedules.find(scheduleId, userId).orElseThrow());
    }

    @Transactional
    public ScheduleView resume(UUID userId, UUID scheduleId) {
        ScheduleRow schedule = lockOwned(userId, scheduleId);
        if ("ACTIVE".equals(schedule.status())) {
            return ScheduleView.from(schedule);
        }
        if (!"PAUSED".equals(schedule.status())) {
            throw ApiException.conflict("SCHEDULE_NOT_PAUSED", "Only a paused schedule can be resumed");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int occurrence = schedule.nextOccurrence();
        OffsetDateTime next = schedule.nextRunAt();
        if (!"ONCE".equals(schedule.frequency())) {
            // Dates that passed while paused are skipped, not run in a burst.
            while (!next.isAfter(now)) {
                occurrence++;
                next = occurrenceAt(schedule.startAt(), schedule.frequency(), occurrence);
            }
        }
        schedules.updateStatus(scheduleId, "ACTIVE", occurrence, next);
        audit.record(userId, "SCHEDULE_RESUMED", "scheduled_transfer", scheduleId.toString(),
                "{\"status\":\"PAUSED\"}", "{\"status\":\"ACTIVE\"}", "transfer", null);
        return ScheduleView.from(schedules.find(scheduleId, userId).orElseThrow());
    }

    private ScheduleRow lockOwned(UUID userId, UUID scheduleId) {
        return schedules.lock(scheduleId, userId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND", "Scheduled transfer not found"));
    }

    /**
     * Runs the most overdue schedule, if there is one. Must be called
     * inside a transaction (the runner opens one per schedule).
     *
     * @return false when nothing was due
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean runNextDue() {
        ScheduleRow schedule = schedules.lockNextDue().orElse(null);
        if (schedule == null) {
            return false;
        }

        String outcome;
        UUID transactionId = null;
        try {
            TransferService.MovementView sent = savepoint.execute(status -> transfers.transfer(
                    schedule.userId(), schedule.sourceAccountId(), schedule.destinationAccountId(),
                    schedule.amount(), schedule.currency(), schedule.description(), null));
            transactionId = sent.transactionId();
            // A first payment to someone new may be put on hold like any
            // other transfer. The run still happened: the money has left.
            outcome = sent.status();
        } catch (ApiException refused) {
            outcome = refused.code();
        } catch (RuntimeException unexpected) {
            // Not a business refusal (a bug, a broken constraint). Keep the
            // occurrence and try again later instead of silently skipping it
            // or blocking every schedule behind this one.
            log.error("scheduled transfer {} failed unexpectedly, postponing", schedule.id(), unexpected);
            schedules.postpone(schedule.id(), OffsetDateTime.now().plusMinutes(5), "ERROR");
            return true;
        }
        boolean completed = "COMPLETED".equals(outcome) || "HELD".equals(outcome);

        String status;
        int nextOccurrence = schedule.nextOccurrence() + 1;
        OffsetDateTime nextRunAt = null;
        if ("ONCE".equals(schedule.frequency())) {
            status = completed ? "COMPLETED" : "FAILED";
        } else {
            status = "ACTIVE";
            OffsetDateTime now = OffsetDateTime.now();
            nextRunAt = occurrenceAt(schedule.startAt(), schedule.frequency(), nextOccurrence);
            // After downtime, skip dates that already passed: one late run
            // is right, a burst of catch-up transfers is not.
            while (!nextRunAt.isAfter(now)) {
                nextOccurrence++;
                nextRunAt = occurrenceAt(schedule.startAt(), schedule.frequency(), nextOccurrence);
            }
        }
        schedules.recordRun(schedule.id(), status, nextOccurrence, nextRunAt, completed, outcome, transactionId);

        if (!completed) {
            log.warn("scheduled transfer {} was refused: {}", schedule.id(), outcome);
            outbox.write("scheduled_transfer", schedule.id(), "ScheduledTransferFailed", "ledger.events",
                    "{\"scheduleId\":\"%s\",\"userId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountMinorUnits\":%d,\"currency\":\"%s\",\"reason\":\"%s\"}"
                            .formatted(schedule.id(), schedule.userId(), schedule.destinationAccountId(),
                                    schedule.amount(), schedule.currency(), outcome));
        }
        return true;
    }

    /**
     * The date of the n-th occurrence, always counted from the first one.
     * Counting from the start (not from the previous run) keeps a schedule
     * that began on the 31st on month ends: Jan 31, Feb 28, Mar 31.
     */
    static OffsetDateTime occurrenceAt(OffsetDateTime startAt, String frequency, int n) {
        return switch (frequency) {
            case "WEEKLY" -> startAt.plusWeeks(n);
            case "MONTHLY" -> startAt.plusMonths(n);
            default -> startAt;
        };
    }
}
