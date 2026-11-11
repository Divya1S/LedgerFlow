package com.ledgerflow.transfer.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.transfer.domain.TransferService.MovementView;
import com.ledgerflow.transfer.persistence.PaymentRequestRepository;
import com.ledgerflow.transfer.persistence.PaymentRequestRepository.RequestRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money requests: "please send me 40.00 for dinner", shared as a link.
 *
 * A request is only an invitation. No money is reserved and nothing moves
 * until someone pays it, and paying it is an ordinary transfer into the
 * requester's account. The request row is locked while it is paid, so a
 * link opened by two people, or clicked twice, is still paid once.
 */
@Service
public class PaymentRequestService {

    private static final int EXPIRY_DAYS = 30;

    private final PaymentRequestRepository requests;
    private final TransferService transfers;
    private final AccountService accounts;
    private final AuditLogger audit;

    public PaymentRequestService(PaymentRequestRepository requests, TransferService transfers,
                                 AccountService accounts, AuditLogger audit) {
        this.requests = requests;
        this.transfers = transfers;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * What both sides may see. The payer learns who is asking, how much
     * and why; the receiving account id stays with the requester.
     */
    public record RequestView(UUID id, String requesterName, long amountMinorUnits, String currency,
                              String note, String status, boolean mine, String payerName,
                              UUID transactionId, OffsetDateTime expiresAt, OffsetDateTime paidAt,
                              OffsetDateTime createdAt) {
        static RequestView from(RequestRow r, UUID viewerUserId) {
            boolean mine = viewerUserId.equals(r.requesterUserId());
            boolean involved = mine || viewerUserId.equals(r.payerUserId());
            return new RequestView(r.id(), r.requesterName(), r.amount(), r.currency(), r.note(),
                    r.effectiveStatus(), mine,
                    involved ? r.payerName() : null,
                    involved ? r.transactionId() : null,
                    r.expiresAt(), r.paidAt(), r.createdAt());
        }
    }

    @Transactional
    public RequestView create(UUID userId, UUID destinationAccountId, long amount, String currency, String note) {
        Account destination = accounts.requireOwnedAccount(destinationAccountId, userId, false);
        if ("CLOSED".equals(destination.status())) {
            throw ApiException.conflict("ACCOUNT_NOT_ACTIVE", "A closed account cannot receive money");
        }
        if (!destination.currency().equals(currency)) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "This account does not hold " + currency);
        }
        UUID id = Uuid7.generate();
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        requests.insert(id, userId, destinationAccountId, amount, currency, cleanNote,
                OffsetDateTime.now().plusDays(EXPIRY_DAYS));
        audit.recordChange(userId, "MONEY_REQUEST_CREATED", "payment_request", id.toString(), null,
                Map.of("amount", amount, "currency", currency), "transfer", null);
        return RequestView.from(requests.findById(id).orElseThrow(), userId);
    }

    public List<RequestView> listMine(UUID userId) {
        return requests.listForRequester(userId).stream().map(r -> RequestView.from(r, userId)).toList();
    }

    /** Anyone signed in who has the link may look at a request before paying it. */
    public RequestView get(UUID viewerUserId, UUID requestId) {
        return RequestView.from(requests.findById(requestId)
                .orElseThrow(() -> notFound()), viewerUserId);
    }

    @Transactional
    public RequestView cancel(UUID userId, UUID requestId) {
        RequestRow request = requests.lockById(requestId).orElseThrow(() -> notFound());
        if (!userId.equals(request.requesterUserId())) {
            throw notFound();
        }
        if ("PAID".equals(request.status())) {
            throw ApiException.conflict("REQUEST_ALREADY_PAID", "This request has already been paid");
        }
        if ("PENDING".equals(request.status())) {
            requests.markCancelled(requestId);
            audit.record(userId, "MONEY_REQUEST_CANCELLED", "payment_request", requestId.toString(),
                    "{\"status\":\"PENDING\"}", "{\"status\":\"CANCELLED\"}", "transfer", null);
        }
        return RequestView.from(requests.findById(requestId).orElseThrow(), userId);
    }

    /**
     * Pays a request from one of the caller's accounts. Runs inside the
     * idempotent endpoint's transaction: the request lock, the transfer
     * and the PAID mark commit together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RequestView pay(UUID payerUserId, UUID requestId, UUID sourceAccountId, UUID idemKeyId) {
        // Ownership first. Checked after the request was loaded, a wrong
        // source would be answered differently depending on whether it
        // happens to be the requester's account, and the payer is not
        // meant to learn which account the money lands in.
        accounts.requireOwnedAccount(sourceAccountId, payerUserId, false);
        RequestRow request = requests.lockById(requestId).orElseThrow(() -> notFound());
        switch (request.effectiveStatus()) {
            case "PAID" -> throw ApiException.conflict("REQUEST_ALREADY_PAID", "This request has already been paid");
            case "CANCELLED" -> throw ApiException.conflict("REQUEST_CANCELLED", "This request was cancelled");
            case "EXPIRED" -> throw ApiException.conflict("REQUEST_EXPIRED", "This request has expired");
            default -> { /* PENDING: payable */ }
        }
        String description = request.note() == null
                ? "Money request from " + request.requesterName()
                : "Money request: " + request.note();
        MovementView movement = transfers.transfer(payerUserId, sourceAccountId,
                request.destinationAccountId(), request.amount(), request.currency(), description, idemKeyId);
        requests.markPaid(requestId, payerUserId, movement.transactionId(), movement.createdAt());
        audit.record(payerUserId, "MONEY_REQUEST_PAID", "payment_request", requestId.toString(),
                "{\"status\":\"PENDING\"}", "{\"status\":\"PAID\"}", "transfer", null);
        return RequestView.from(requests.findById(requestId).orElseThrow(), payerUserId);
    }

    private static ApiException notFound() {
        return ApiException.notFound("REQUEST_NOT_FOUND", "Money request not found");
    }
}
