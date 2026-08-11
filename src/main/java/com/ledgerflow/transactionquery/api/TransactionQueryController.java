package com.ledgerflow.transactionquery.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.TransactionView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read side for transaction history. Phase 2 ships simple limited listings;
 * Phase 4 replaces the paging with measured keyset pagination (see
 * docs/query-optimization.md for why OFFSET does not survive deep pages).
 */
@RestController
@Validated
@RequestMapping("/api/v1")
public class TransactionQueryController {

    private final TransactionQueryRepository transactions;
    private final AccountService accountService;

    public TransactionQueryController(TransactionQueryRepository transactions, AccountService accountService) {
        this.transactions = transactions;
        this.accountService = accountService;
    }

    public record TransactionResponse(
            UUID id, String type, String status, long amountMinorUnits, String currency,
            UUID sourceAccountId, UUID destinationAccountId, String description, OffsetDateTime createdAt) {

        static TransactionResponse from(TransactionView t) {
            return new TransactionResponse(t.id(), t.type(), t.status(), t.amount(), t.currency(),
                    t.sourceAccountId(), t.destinationAccountId(), t.description(), t.createdAt());
        }
    }

    @GetMapping("/transactions")
    List<TransactionResponse> myTransactions(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        CurrentUser user = CurrentUser.from(jwt);
        return transactions.findForUser(user.id(), limit).stream()
                .map(TransactionResponse::from).toList();
    }

    @GetMapping("/transactions/{id}")
    TransactionResponse byId(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        TransactionView t = transactions.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found"));
        if (!user.isAdmin() && !transactions.involvesUser(id, user.id())) {
            throw ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found");
        }
        return TransactionResponse.from(t);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    List<TransactionResponse> byAccount(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID accountId,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.requireOwnedAccount(accountId, user.id(), user.isAdmin());
        return transactions.findForAccount(accountId, limit).stream()
                .map(TransactionResponse::from).toList();
    }
}
