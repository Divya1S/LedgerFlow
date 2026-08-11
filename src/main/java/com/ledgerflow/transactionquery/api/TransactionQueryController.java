package com.ledgerflow.transactionquery.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.Balance;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.StatementLine;
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
 * Read side for transaction history and account statements. All listings
 * use keyset pagination: pass the returned nextCursor to get the next page.
 */
@RestController
@Validated
@RequestMapping("/api/v1")
public class TransactionQueryController {

    private final TransactionQueryRepository transactions;
    private final AccountService accountService;
    private final com.ledgerflow.common.cache.RedisSafeCache cache;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public TransactionQueryController(TransactionQueryRepository transactions, AccountService accountService,
                                      com.ledgerflow.common.cache.RedisSafeCache cache,
                                      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.accountService = accountService;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    public record TransactionResponse(
            UUID id, String type, String status, long amountMinorUnits, String currency,
            UUID sourceAccountId, UUID destinationAccountId, String description, OffsetDateTime createdAt) {

        static TransactionResponse from(TransactionView t) {
            return new TransactionResponse(t.id(), t.type(), t.status(), t.amount(), t.currency(),
                    t.sourceAccountId(), t.destinationAccountId(), t.description(), t.createdAt());
        }
    }

    public record PageResponse<T>(List<T> items, String nextCursor) {
    }

    public record StatementLineResponse(
            UUID entryId, UUID transactionId, long amountMinorUnits, String direction,
            long balanceAfterMinorUnits, String currency, OffsetDateTime createdAt) {
    }

    @GetMapping("/transactions")
    PageResponse<TransactionResponse> myTransactions(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        CurrentUser user = CurrentUser.from(jwt);
        Cursor c = cursor == null ? null : Cursor.decode(cursor);
        List<TransactionView> page = transactions.findForUser(user.id(),
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);
        return toPage(page, limit);
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

    /**
     * The first page (no cursor, default limit) is cache-aside in Redis
     * with a 30s TTL; money movements evict the touched accounts' keys
     * after commit. Cursored pages are immutable-ish history and cheap by
     * index, so they bypass the cache. X-Cache exposes HIT/MISS/BYPASS.
     */
    @GetMapping("/accounts/{accountId}/transactions")
    org.springframework.http.ResponseEntity<String> byAccount(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID accountId,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.requireOwnedAccount(accountId, user.id(), user.isAdmin());

        boolean cacheable = cursor == null && limit == 50;
        if (cacheable) {
            String hit = cache.get(historyCacheKey(accountId));
            if (hit != null) {
                return jsonResponse(hit, "HIT");
            }
        }
        Cursor c = cursor == null ? null : Cursor.decode(cursor);
        List<TransactionView> page = transactions.findForAccount(accountId,
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);
        String body = objectMapper.writeValueAsString(toPage(page, limit));
        if (cacheable) {
            cache.put(historyCacheKey(accountId), body, java.time.Duration.ofSeconds(30));
            return jsonResponse(body, "MISS");
        }
        return jsonResponse(body, "BYPASS");
    }

    private static String historyCacheKey(UUID accountId) {
        return com.ledgerflow.common.cache.CacheKeys.accountHistory(accountId);
    }

    private org.springframework.http.ResponseEntity<String> jsonResponse(String body, String cacheStatus) {
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("X-Cache", cacheStatus)
                .body(body);
    }

    /**
     * Account statement: ledger entries newest first with the balance after
     * each entry, computed with a window function from the current balance
     * (first page) or the cursor's carried balance (later pages).
     */
    @GetMapping("/accounts/{accountId}/statement")
    PageResponse<StatementLineResponse> statement(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID accountId,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.requireOwnedAccount(accountId, user.id(), user.isAdmin());

        StatementCursor c = cursor == null ? null : StatementCursor.decode(cursor);
        long startBalance;
        if (c == null) {
            Balance balance = accountService.balanceOf(accountId, user.id(), user.isAdmin());
            startBalance = balance.balance();
        } else {
            startBalance = c.balanceBefore();
        }

        List<StatementLine> lines = transactions.statement(accountId, startBalance,
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);

        String nextCursor = null;
        if (lines.size() == limit) {
            StatementLine last = lines.getLast();
            nextCursor = new StatementCursor(last.createdAt(), last.entryId(),
                    last.balanceAfter() - last.amount()).encode();
        }
        return new PageResponse<>(lines.stream()
                .map(l -> new StatementLineResponse(l.entryId(), l.transactionId(), l.amount(),
                        l.direction(), l.balanceAfter(), l.currency(), l.createdAt()))
                .toList(), nextCursor);
    }

    private PageResponse<TransactionResponse> toPage(List<TransactionView> page, int limit) {
        String nextCursor = null;
        if (page.size() == limit) {
            TransactionView last = page.getLast();
            nextCursor = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new PageResponse<>(page.stream().map(TransactionResponse::from).toList(), nextCursor);
    }
}
