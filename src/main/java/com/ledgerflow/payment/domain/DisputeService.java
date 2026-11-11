package com.ledgerflow.payment.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.outbox.OutboxWriter;
import com.ledgerflow.payment.persistence.DisputeRepository;
import com.ledgerflow.payment.persistence.DisputeRepository.DisputeRow;
import com.ledgerflow.payment.persistence.PaymentRepository;
import com.ledgerflow.payment.persistence.PaymentRepository.PaymentRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Disputes: a payer saying "this payment was wrong" and what happens next.
 *
 *   OPEN          merchant refunds    ->  RESOLVED_CUSTOMER
 *   OPEN          merchant declines   ->  UNDER_REVIEW
 *   UNDER_REVIEW  platform refunds    ->  RESOLVED_CUSTOMER
 *   UNDER_REVIEW  platform declines   ->  RESOLVED_MERCHANT
 *
 * A merchant can always give the money back, but cannot close a dispute
 * in its own favour: declining hands the decision to the platform. That
 * is the same shape card disputes have, with the platform standing in for
 * the card network.
 *
 * Resolving in the customer's favour refunds whatever has not been
 * refunded yet, through the ordinary refund path, in the same database
 * transaction as the status change. A dispute can never say "refunded"
 * while the money stayed put, or the other way round.
 */
@Service
public class DisputeService {

    private final DisputeRepository disputes;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final OutboxWriter outbox;
    private final AuditLogger audit;

    public DisputeService(DisputeRepository disputes, PaymentRepository payments,
                          PaymentService paymentService, OutboxWriter outbox, AuditLogger audit) {
        this.disputes = disputes;
        this.payments = payments;
        this.paymentService = paymentService;
        this.outbox = outbox;
        this.audit = audit;
    }

    public record DisputeView(UUID disputeId, UUID paymentId, String status, String reason,
                              String merchantResponse, String resolutionNote,
                              long amountMinorUnits, long refundedMinorUnits, String currency,
                              String payerName, String merchantName, String role, boolean canDecide,
                              OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
    }

    @Transactional
    public DisputeView open(UUID callerUserId, UUID paymentId, String reason) {
        PaymentRow payment = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (!callerUserId.equals(payment.payerUserId())) {
            // Only the person who paid can dispute; everyone else gets the
            // same answer as for a payment that does not exist.
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found");
        }
        if (!"COMPLETED".equals(payment.status()) && !"PARTIALLY_REFUNDED".equals(payment.status())) {
            throw ApiException.conflict("PAYMENT_NOT_DISPUTABLE",
                    "A payment that is " + payment.status().toLowerCase().replace('_', ' ')
                            + " cannot be disputed");
        }
        UUID disputeId = Uuid7.generate();
        try {
            disputes.insert(disputeId, paymentId, callerUserId, reason.strip());
        } catch (DuplicateKeyException e) {
            // The partial unique index allows one open dispute per payment.
            throw ApiException.conflict("DISPUTE_ALREADY_OPEN", "This payment already has an open dispute");
        }
        audit.recordChange(callerUserId, "DISPUTE_OPENED", "dispute", disputeId.toString(),
                null, Map.of("paymentId", paymentId.toString(), "reason", reason.strip()), "payment", null);
        outbox.write("dispute", disputeId, "DisputeOpened", "payment.events",
                "{\"disputeId\":\"%s\",\"paymentId\":\"%s\",\"openedBy\":\"%s\"}"
                        .formatted(disputeId, paymentId, callerUserId));
        return view(disputes.findById(disputeId).orElseThrow(), callerUserId, false);
    }

    public List<DisputeView> list(UUID callerUserId, boolean callerIsAdmin) {
        return disputes.listVisibleTo(callerUserId, callerIsAdmin).stream()
                .map(row -> view(row, callerUserId, callerIsAdmin))
                .toList();
    }

    /**
     * Records a decision on a dispute. Runs inside the idempotent endpoint's
     * transaction because the REFUND outcome moves money: a retried request
     * replays the stored answer instead of refunding twice.
     *
     * @param outcome REFUND (customer gets the money back) or DECLINE
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DisputeView decide(UUID callerUserId, boolean callerIsAdmin, UUID disputeId,
                              String outcome, String note, UUID idemKeyId) {
        DisputeRow dispute = disputes.lockById(disputeId)
                .orElseThrow(() -> ApiException.notFound("DISPUTE_NOT_FOUND", "Dispute not found"));
        boolean isMerchant = callerUserId.equals(dispute.merchantUserId());
        if (!isMerchant && !callerIsAdmin) {
            throw ApiException.notFound("DISPUTE_NOT_FOUND", "Dispute not found");
        }
        if (dispute.status().startsWith("RESOLVED")) {
            throw ApiException.conflict("DISPUTE_ALREADY_RESOLVED", "This dispute has already been resolved");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();

        String newStatus;
        if ("REFUND".equals(outcome)) {
            PaymentRow payment = payments.findById(dispute.paymentId()).orElseThrow();
            if (payment.amount() > payment.refundedAmount()) {
                // Refund what is left. If the merchant cannot cover it the
                // refund throws, the transaction rolls back and the dispute
                // stays open: no half-resolved state.
                paymentService.refund(callerUserId, callerIsAdmin, dispute.paymentId(), null,
                        "Dispute resolved for the customer", idemKeyId);
            }
            newStatus = "RESOLVED_CUSTOMER";
            disputes.resolve(disputeId, newStatus, cleanNote, callerUserId);
        } else if (callerIsAdmin) {
            newStatus = "RESOLVED_MERCHANT";
            disputes.resolve(disputeId, newStatus, cleanNote, callerUserId);
        } else if ("OPEN".equals(dispute.status())) {
            newStatus = "UNDER_REVIEW";
            disputes.escalate(disputeId, cleanNote);
        } else {
            throw ApiException.conflict("DISPUTE_UNDER_REVIEW",
                    "You have already declined this dispute; it is waiting for a platform decision");
        }

        audit.record(callerUserId, "DISPUTE_" + newStatus, "dispute", disputeId.toString(),
                "{\"status\":\"" + dispute.status() + "\"}", "{\"status\":\"" + newStatus + "\"}",
                "payment", cleanNote);
        outbox.write("dispute", disputeId, "DisputeUpdated", "payment.events",
                "{\"disputeId\":\"%s\",\"paymentId\":\"%s\",\"status\":\"%s\",\"payerUserId\":\"%s\"}"
                        .formatted(disputeId, dispute.paymentId(), newStatus, dispute.payerUserId()));
        return view(disputes.findById(disputeId).orElseThrow(), callerUserId, callerIsAdmin);
    }

    private DisputeView view(DisputeRow row, UUID callerUserId, boolean callerIsAdmin) {
        boolean isMerchant = callerUserId.equals(row.merchantUserId());
        boolean isCustomer = callerUserId.equals(row.openedBy());
        // Someone who paid their own shop is both; the merchant side is the
        // one with something to do, so that is the role reported.
        String role = isMerchant ? "MERCHANT" : isCustomer ? "CUSTOMER" : "ADMIN";
        boolean canDecide = switch (row.status()) {
            case "OPEN" -> isMerchant || callerIsAdmin;
            case "UNDER_REVIEW" -> callerIsAdmin || isMerchant; // a merchant may still refund
            default -> false;
        };
        return new DisputeView(row.id(), row.paymentId(), row.status(), row.reason(),
                row.merchantResponse(), row.resolutionNote(), row.amount(), row.refundedAmount(),
                row.currency(), row.payerName(), row.merchantName(), role, canDecide,
                row.createdAt(), row.resolvedAt());
    }
}
