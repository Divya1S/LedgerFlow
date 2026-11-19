package com.ledgerflow.transfer.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.account.domain.SystemAccounts;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.ledger.domain.MoneyMovementService;
import com.ledgerflow.ledger.domain.MoneyMovementService.MovementResult;
import com.ledgerflow.ledger.domain.Posting;
import com.ledgerflow.outbox.OutboxWriter;
import com.ledgerflow.transfer.domain.TransferProtection.Assessment;
import com.ledgerflow.transfer.persistence.PaymentRequestRepository;
import com.ledgerflow.transfer.persistence.TransferHoldRepository;
import com.ledgerflow.transfer.persistence.TransferHoldRepository.HoldRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfers on hold: sent, but not final yet.
 *
 * The money leaves the sender's wallet at once and waits in the platform
 * suspense account, so it cannot be spent twice and the sender's balance
 * is honest. From there it has exactly two ways out, and the row lock on
 * the hold makes sure only one of them ever happens:
 *
 *   released  to the recipient, automatically, at release_at
 *   cancelled back to the sender, any time before that
 *
 * Three promises this class keeps, because a hold nobody can see through
 * is its own kind of harm:
 *
 *  - It ends by itself. No person has to approve a release. The time
 *    shown to the sender when they send is the time the money arrives.
 *  - It says why. The reasons are stored with the hold and shown to the
 *    sender (never to the recipient).
 *  - It is exact. Each step is a balanced ledger transaction, and the
 *    suspense balance always equals the total of open holds.
 */
@Service
public class TransferHoldService {

    private static final Logger log = LoggerFactory.getLogger(TransferHoldService.class);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final TransferHoldRepository holds;
    private final PaymentRequestRepository requests;
    private final MoneyMovementService movements;
    private final SystemAccounts systemAccounts;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate savepoint;

    public TransferHoldService(TransferHoldRepository holds, PaymentRequestRepository requests,
                               MoneyMovementService movements, SystemAccounts systemAccounts,
                               OutboxWriter outbox, AuditLogger audit, ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.holds = holds;
        this.requests = requests;
        this.movements = movements;
        this.systemAccounts = systemAccounts;
        this.outbox = outbox;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * A hold as one side sees it. The sender gets the reasons and can
     * cancel. The recipient gets who, how much and when: never the reasons,
     * which may be about them.
     */
    public record HoldView(UUID id, String status, String direction, long amountMinorUnits, String currency,
                           String description, String counterparty, String accountName,
                           String riskLevel, List<String> reasons, OffsetDateTime releaseAt,
                           OffsetDateTime createdAt, OffsetDateTime resolvedAt, UUID transactionId,
                           boolean canCancel) {
    }

    public record Holds(List<HoldView> outgoing, List<HoldView> incoming, long heldOutMinorUnits) {
    }

    /** What placing a hold produced, for the transfer endpoint's response. */
    public record Placed(UUID holdId, UUID transactionId, OffsetDateTime createdAt, OffsetDateTime releaseAt) {
    }

    /**
     * Moves the money into suspense and records the hold. Runs inside the
     * caller's transaction (the idempotent transfer endpoint), so the
     * debit, the hold row and the event commit together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Placed place(UUID senderUserId, UUID sourceAccountId, UUID destinationAccountId, long amount,
                        String currency, String description, UUID idemKeyId, Assessment assessment) {
        UUID suspense = systemAccounts.suspenseAccount(currency);
        // The transaction names the two people it is between; the entries
        // say where the money is right now.
        MovementResult held = movements.post("HOLD", amount, currency, sourceAccountId, destinationAccountId,
                description, idemKeyId,
                List.of(new Posting(sourceAccountId, -amount), new Posting(suspense, amount)));

        UUID holdId = Uuid7.generate();
        OffsetDateTime releaseAt = held.createdAt().plusMinutes(assessment.holdMinutes());
        holds.insert(holdId, senderUserId, assessment.recipientUserId(), sourceAccountId, destinationAccountId,
                amount, currency, description, assessment.riskLevel(), toJson(assessment.reasons()), releaseAt,
                held.transactionId(), held.createdAt());

        audit.recordChange(senderUserId, "TRANSFER_HELD", "transfer_hold", holdId.toString(), null,
                Map.of("amount", amount, "currency", currency, "riskLevel", assessment.riskLevel(),
                        "reasons", assessment.reasons(), "releaseAt", releaseAt.toString()),
                "transfer", null);
        outbox.write("transfer_hold", holdId, "TransferHeld", "ledger.events",
                event(holdId, senderUserId, assessment.recipientUserId(), sourceAccountId,
                        destinationAccountId, amount, currency, releaseAt));
        return new Placed(holdId, held.transactionId(), held.createdAt(), releaseAt);
    }

    /**
     * The sender takes the money back. Moves money, so it runs inside the
     * idempotent endpoint's transaction: a retried cancel replays the
     * stored answer instead of trying to return the money twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HoldView cancel(UUID callerUserId, UUID holdId, UUID idemKeyId) {
        HoldRow hold = holds.lock(holdId)
                .orElseThrow(() -> ApiException.notFound("HOLD_NOT_FOUND", "Held transfer not found"));
        if (!callerUserId.equals(hold.senderUserId())) {
            // The recipient can see the hold but only the sender can cancel it.
            throw ApiException.notFound("HOLD_NOT_FOUND", "Held transfer not found");
        }
        if (!"HELD".equals(hold.status())) {
            throw ApiException.conflict("HOLD_ALREADY_SETTLED",
                    "RELEASED".equals(hold.status())
                            ? "This transfer has already been delivered and can no longer be cancelled"
                            : "This transfer has already been cancelled");
        }
        giveBack(hold, "CANCELLED", idemKeyId);
        audit.record(callerUserId, "TRANSFER_HOLD_CANCELLED", "transfer_hold", holdId.toString(),
                "{\"status\":\"HELD\"}", "{\"status\":\"CANCELLED\"}", "transfer", null);
        outbox.write("transfer_hold", holdId, "TransferHoldCancelled", "ledger.events", event(hold));
        return view(holds.find(holdId).orElseThrow(), callerUserId);
    }

    /**
     * Releases the hold that has been due longest, if any. Must be called
     * inside a transaction (the runner opens one per hold): the lock, the
     * credit to the recipient and the status change commit together, so a
     * crash can never deliver money and leave the hold looking open.
     *
     * @return false when nothing was due
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseNextDue() {
        HoldRow hold = holds.lockNextDue().orElse(null);
        if (hold == null) {
            return false;
        }
        UUID suspense = systemAccounts.suspenseAccount(hold.currency());
        try {
            MovementResult released = savepoint.execute(status -> movements.post("HOLD_RELEASE",
                    hold.amount(), hold.currency(), hold.sourceAccountId(), hold.destinationAccountId(),
                    hold.description(), null,
                    List.of(new Posting(suspense, -hold.amount()),
                            new Posting(hold.destinationAccountId(), hold.amount()))));
            holds.settle(hold.id(), "RELEASED", released.transactionId(), released.createdAt());
            audit.record(null, "TRANSFER_HOLD_RELEASED", "transfer_hold", hold.id().toString(),
                    "{\"status\":\"HELD\"}", "{\"status\":\"RELEASED\"}", "transfer", null);
            outbox.write("transfer_hold", hold.id(), "TransferHoldReleased", "ledger.events", event(hold));
        } catch (ApiException refused) {
            // The recipient can no longer receive it. The sender's money
            // does not stay in limbo: it goes straight back.
            log.warn("held transfer {} could not be delivered ({}), returning it", hold.id(), refused.code());
            try {
                savepoint.executeWithoutResult(status -> giveBack(hold, "RETURNED", null));
                outbox.write("transfer_hold", hold.id(), "TransferHoldReturned", "ledger.events", event(hold));
            } catch (RuntimeException stuck) {
                log.error("held transfer {} could be neither delivered nor returned, retrying later",
                        hold.id(), stuck);
                holds.postpone(hold.id(), OffsetDateTime.now().plusMinutes(5));
            }
        } catch (RuntimeException unexpected) {
            log.error("held transfer {} failed to release, retrying later", hold.id(), unexpected);
            holds.postpone(hold.id(), OffsetDateTime.now().plusMinutes(5));
        }
        return true;
    }

    /** Sends held money back to the wallet it came from and closes the hold. */
    private void giveBack(HoldRow hold, String status, UUID idemKeyId) {
        UUID suspense = systemAccounts.suspenseAccount(hold.currency());
        MovementResult returned = movements.post("HOLD_RETURN", hold.amount(), hold.currency(),
                suspense, hold.sourceAccountId(), hold.description(), idemKeyId,
                List.of(new Posting(suspense, -hold.amount()),
                        new Posting(hold.sourceAccountId(), hold.amount())));
        holds.settle(hold.id(), status, returned.transactionId(), returned.createdAt());
        // If this hold was paying a money request, that request is unpaid again.
        requests.reopenPaidBy(hold.holdTransactionId());
    }

    public Holds list(UUID userId) {
        List<HoldView> outgoing = holds.listSentBy(userId).stream().map(h -> view(h, userId)).toList();
        List<HoldView> incoming = holds.listArrivingFor(userId).stream().map(h -> view(h, userId)).toList();
        long heldOut = outgoing.stream().filter(h -> "HELD".equals(h.status()))
                .mapToLong(HoldView::amountMinorUnits).sum();
        return new Holds(outgoing, incoming, heldOut);
    }

    private HoldView view(HoldRow h, UUID viewerUserId) {
        boolean sender = viewerUserId.equals(h.senderUserId());
        return new HoldView(h.id(), h.status(), sender ? "OUTGOING" : "INCOMING",
                h.amount(), h.currency(), h.description(),
                sender ? h.recipientLabel() : h.senderName(),
                sender ? h.sourceName() : h.destinationName(),
                sender ? h.riskLevel() : null,
                sender ? fromJson(h.reasonsJson()) : List.of(),
                h.releaseAt(), h.createdAt(), h.resolvedAt(),
                sender ? h.holdTransactionId() : null,
                sender && "HELD".equals(h.status()));
    }

    private String event(HoldRow h) {
        return event(h.id(), h.senderUserId(), h.recipientUserId(), h.sourceAccountId(),
                h.destinationAccountId(), h.amount(), h.currency(), h.releaseAt());
    }

    private static String event(UUID holdId, UUID sender, UUID recipient, UUID source, UUID destination,
                                long amount, String currency, OffsetDateTime releaseAt) {
        return "{\"holdId\":\"%s\",\"senderUserId\":\"%s\",\"recipientUserId\":\"%s\",\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountMinorUnits\":%d,\"currency\":\"%s\",\"releaseAt\":\"%s\"}"
                .formatted(holdId, sender, recipient, source, destination, amount, currency, releaseAt);
    }

    private String toJson(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize hold reasons", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
