package com.ledgerflow.transfer.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.SystemAccounts;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.ledger.domain.MoneyMovementService;
import com.ledgerflow.ledger.domain.MoneyMovementService.MovementResult;
import com.ledgerflow.ledger.domain.Posting;
import com.ledgerflow.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers between accounts plus deposits and withdrawals against the
 * platform cash account. All methods require an existing transaction:
 * the idempotency layer opens it, so key claim, movement and outbox row
 * commit atomically.
 */
@Service
public class TransferService {

    private final MoneyMovementService movements;
    private final AccountService accountService;
    private final SystemAccounts systemAccounts;
    private final OutboxWriter outbox;
    private final AuditLogger audit;

    public TransferService(MoneyMovementService movements, AccountService accountService,
                           SystemAccounts systemAccounts, OutboxWriter outbox, AuditLogger audit) {
        this.movements = movements;
        this.accountService = accountService;
        this.systemAccounts = systemAccounts;
        this.outbox = outbox;
        this.audit = audit;
    }

    public record MovementView(UUID transactionId, String type, String status, long amountMinorUnits,
                               String currency, UUID sourceAccountId, UUID destinationAccountId,
                               OffsetDateTime createdAt) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView transfer(UUID userId, UUID sourceAccountId, UUID destinationAccountId,
                                 long amount, String currency, String description, UUID idemKeyId) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Source and destination must differ");
        }
        // Source must be the caller's own wallet. Destination only has to
        // exist and be active; the movement service verifies that under lock.
        accountService.requireOwnedAccount(sourceAccountId, userId, false);

        MovementResult result = movements.post("TRANSFER", amount, currency,
                sourceAccountId, destinationAccountId, description, idemKeyId,
                List.of(new Posting(sourceAccountId, -amount),
                        new Posting(destinationAccountId, amount)));

        audit.record(userId, "TRANSFER_COMPLETED", "transaction", result.transactionId().toString(),
                null, "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}", "transfer", null);
        outbox.write("transaction", result.transactionId(), "TransferCompleted", "ledger.events",
                eventJson(result.transactionId(), "TRANSFER", amount, currency,
                        sourceAccountId, destinationAccountId));
        return view(result, "TRANSFER", amount, currency, sourceAccountId, destinationAccountId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView deposit(UUID userId, UUID accountId, long amount, String currency,
                                String description, UUID idemKeyId) {
        accountService.requireOwnedAccount(accountId, userId, false);
        UUID cash = systemAccounts.cashAccount(currency);

        MovementResult result = movements.post("DEPOSIT", amount, currency,
                cash, accountId, description, idemKeyId,
                List.of(new Posting(cash, -amount),
                        new Posting(accountId, amount)));

        audit.record(userId, "DEPOSIT_COMPLETED", "transaction", result.transactionId().toString(),
                null, "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}", "transfer", null);
        outbox.write("transaction", result.transactionId(), "DepositCompleted", "ledger.events",
                eventJson(result.transactionId(), "DEPOSIT", amount, currency, cash, accountId));
        return view(result, "DEPOSIT", amount, currency, cash, accountId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MovementView withdraw(UUID userId, UUID accountId, long amount, String currency,
                                 String description, UUID idemKeyId) {
        accountService.requireOwnedAccount(accountId, userId, false);
        UUID cash = systemAccounts.cashAccount(currency);

        MovementResult result = movements.post("WITHDRAWAL", amount, currency,
                accountId, cash, description, idemKeyId,
                List.of(new Posting(accountId, -amount),
                        new Posting(cash, amount)));

        audit.record(userId, "WITHDRAWAL_COMPLETED", "transaction", result.transactionId().toString(),
                null, "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}", "transfer", null);
        outbox.write("transaction", result.transactionId(), "WithdrawalCompleted", "ledger.events",
                eventJson(result.transactionId(), "WITHDRAWAL", amount, currency, accountId, cash));
        return view(result, "WITHDRAWAL", amount, currency, accountId, cash);
    }

    private MovementView view(MovementResult result, String type, long amount, String currency,
                              UUID source, UUID destination) {
        return new MovementView(result.transactionId(), type, "COMPLETED", amount, currency,
                source, destination, result.createdAt());
    }

    private String eventJson(UUID transactionId, String type, long amount, String currency,
                             UUID source, UUID destination) {
        return "{\"transactionId\":\"%s\",\"type\":\"%s\",\"amountMinorUnits\":%d,\"currency\":\"%s\",\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\"}"
                .formatted(transactionId, type, amount, currency, source, destination);
    }
}
