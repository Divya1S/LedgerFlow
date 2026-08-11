package com.ledgerflow.payment.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.SystemAccounts;
import com.ledgerflow.account.persistence.AccountRepository;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.ledger.domain.MoneyMovementService;
import com.ledgerflow.ledger.domain.MoneyMovementService.MovementResult;
import com.ledgerflow.ledger.domain.Posting;
import com.ledgerflow.outbox.OutboxWriter;
import com.ledgerflow.payment.persistence.PaymentRepository;
import com.ledgerflow.payment.persistence.PaymentRepository.PaymentRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments from a user wallet to a merchant account, with a platform fee,
 * and full or partial refunds.
 *
 * Fee model: 1% of the amount, rounded down, credited to SYSTEM_FEES. On
 * refund the fee is returned proportionally: refunding X of an original
 * amount A returns fee*X/A (rounded down) from SYSTEM_FEES and the rest
 * from the merchant, so the payer always gets exactly X back and every
 * movement stays balanced.
 */
@Service
public class PaymentService {

    static final long FEE_BASIS_POINTS = 100; // 1%

    private final MoneyMovementService movements;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final SystemAccounts systemAccounts;
    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final AuditLogger audit;

    public PaymentService(MoneyMovementService movements, AccountService accountService,
                          AccountRepository accounts, SystemAccounts systemAccounts,
                          PaymentRepository payments, OutboxWriter outbox, AuditLogger audit) {
        this.movements = movements;
        this.accountService = accountService;
        this.accounts = accounts;
        this.systemAccounts = systemAccounts;
        this.payments = payments;
        this.outbox = outbox;
        this.audit = audit;
    }

    public record PaymentView(UUID paymentId, UUID transactionId, String status,
                              long amountMinorUnits, long feeMinorUnits, long refundedMinorUnits,
                              String currency, UUID sourceAccountId, UUID destinationAccountId,
                              OffsetDateTime createdAt) {
    }

    public record RefundView(UUID refundId, UUID paymentId, UUID transactionId, String status,
                             long amountMinorUnits, String paymentStatus, OffsetDateTime createdAt) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentView pay(UUID userId, UUID sourceAccountId, UUID destinationAccountId,
                           long amount, String currency, String description, UUID idemKeyId) {
        accountService.requireOwnedAccount(sourceAccountId, userId, false);
        Account destination = accounts.findById(destinationAccountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Destination account not found"));
        if (!"MERCHANT".equals(destination.type())) {
            throw ApiException.unprocessable("NOT_A_MERCHANT",
                    "Payments can only be made to merchant accounts; use transfers between wallets");
        }

        long fee = fee(amount);
        UUID paymentId = Uuid7.generate();
        payments.insertPending(paymentId, userId, sourceAccountId, destinationAccountId, amount, currency);

        List<Posting> postings = new ArrayList<>(List.of(
                new Posting(sourceAccountId, -amount),
                new Posting(destinationAccountId, amount - fee)));
        if (fee > 0) {
            postings.add(new Posting(systemAccounts.feesAccount(currency), fee));
        }
        MovementResult result = movements.post("PAYMENT", amount, currency,
                sourceAccountId, destinationAccountId, description, idemKeyId, postings);
        payments.markCompleted(paymentId, result.transactionId(), result.createdAt());

        audit.record(userId, "PAYMENT_COMPLETED", "payment", paymentId.toString(),
                null, "{\"amount\":" + amount + ",\"fee\":" + fee + "}", "payment", null);
        outbox.write("payment", paymentId, "PaymentCompleted", "payment.events",
                "{\"paymentId\":\"%s\",\"transactionId\":\"%s\",\"payerUserId\":\"%s\",\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountMinorUnits\":%d,\"feeMinorUnits\":%d,\"currency\":\"%s\"}"
                        .formatted(paymentId, result.transactionId(), userId, sourceAccountId,
                                destinationAccountId, amount, fee, currency));

        return new PaymentView(paymentId, result.transactionId(), "COMPLETED", amount, fee, 0,
                currency, sourceAccountId, destinationAccountId, result.createdAt());
    }

    /**
     * Refunds are issued by the merchant account owner (or an admin), not
     * the payer: the merchant received the money, so the merchant gives it
     * back. The payment row lock serializes concurrent refunds and the
     * refunded_amount CHECK is the database backstop against over-refunding.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundView refund(UUID callerUserId, boolean callerIsAdmin, UUID paymentId,
                             Long requestedAmount, String reason, UUID idemKeyId) {
        PaymentRow payment = payments.lockById(paymentId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));

        Account merchant = accounts.findById(payment.destinationAccountId()).orElseThrow();
        if (!callerIsAdmin && !callerUserId.equals(merchant.userId())) {
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found");
        }
        if (!"COMPLETED".equals(payment.status()) && !"PARTIALLY_REFUNDED".equals(payment.status())) {
            throw ApiException.conflict("PAYMENT_NOT_REFUNDABLE",
                    "Payment in status " + payment.status() + " cannot be refunded");
        }

        long refundable = payment.amount() - payment.refundedAmount();
        long refundAmount = requestedAmount != null ? requestedAmount : refundable;
        if (refundAmount <= 0 || refundAmount > refundable) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_PAYMENT",
                    "Refundable amount is " + refundable);
        }

        long originalFee = fee(payment.amount());
        long feeReturn = originalFee * refundAmount / payment.amount();
        long merchantReturn = refundAmount - feeReturn;

        List<Posting> postings = new ArrayList<>(List.of(
                new Posting(payment.destinationAccountId(), -merchantReturn),
                new Posting(payment.sourceAccountId(), refundAmount)));
        if (feeReturn > 0) {
            postings.add(new Posting(systemAccounts.feesAccount(payment.currency()), -feeReturn));
        }
        MovementResult result = movements.post("REFUND", refundAmount, payment.currency(),
                payment.destinationAccountId(), payment.sourceAccountId(), reason, idemKeyId, postings);

        String newStatus = (payment.refundedAmount() + refundAmount == payment.amount())
                ? "REFUNDED" : "PARTIALLY_REFUNDED";
        payments.applyRefund(paymentId, refundAmount, newStatus);

        UUID refundId = Uuid7.generate();
        payments.insertRefund(refundId, paymentId, refundAmount, reason,
                result.transactionId(), result.createdAt());

        audit.record(callerUserId, "REFUND_COMPLETED", "payment", paymentId.toString(),
                "{\"refunded\":" + payment.refundedAmount() + "}",
                "{\"refunded\":" + (payment.refundedAmount() + refundAmount) + ",\"status\":\"" + newStatus + "\"}",
                "payment", reason);
        outbox.write("payment", paymentId, "RefundCompleted", "payment.events",
                "{\"paymentId\":\"%s\",\"refundId\":\"%s\",\"transactionId\":\"%s\",\"amountMinorUnits\":%d,\"paymentStatus\":\"%s\"}"
                        .formatted(paymentId, refundId, result.transactionId(), refundAmount, newStatus));

        return new RefundView(refundId, paymentId, result.transactionId(), "COMPLETED",
                refundAmount, newStatus, result.createdAt());
    }

    public PaymentView getPayment(UUID callerUserId, boolean callerIsAdmin, UUID paymentId) {
        PaymentRow payment = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        Account merchant = accounts.findById(payment.destinationAccountId()).orElseThrow();
        boolean isPayer = callerUserId.equals(payment.payerUserId());
        boolean isMerchant = callerUserId.equals(merchant.userId());
        if (!callerIsAdmin && !isPayer && !isMerchant) {
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found");
        }
        return new PaymentView(payment.id(), payment.transactionId(), payment.status(),
                payment.amount(), fee(payment.amount()), payment.refundedAmount(), payment.currency(),
                payment.sourceAccountId(), payment.destinationAccountId(), payment.createdAt());
    }

    private long fee(long amount) {
        return amount * FEE_BASIS_POINTS / 10_000;
    }
}
