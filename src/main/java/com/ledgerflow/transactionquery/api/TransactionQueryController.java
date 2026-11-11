package com.ledgerflow.transactionquery.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.Balance;
import com.ledgerflow.common.cache.CacheKeys;
import com.ledgerflow.common.cache.RedisSafeCache;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transactionquery.domain.StatementExport;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.EntryView;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.Filter;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.StatementLine;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.TransactionDetail;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.TransactionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@Tag(name = "Transactions", description = "History, search, statements and exports")
public class TransactionQueryController {

    private final TransactionQueryRepository transactions;
    private final AccountService accountService;
    private final StatementExport statementExport;
    private final RedisSafeCache cache;
    private final ObjectMapper objectMapper;

    public TransactionQueryController(TransactionQueryRepository transactions, AccountService accountService,
                                      StatementExport statementExport, RedisSafeCache cache,
                                      ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.accountService = accountService;
        this.statementExport = statementExport;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /**
     * direction is from the caller's point of view: OUT left their
     * accounts, IN arrived, INTERNAL moved between two of their own.
     * counterparty names the other side (for INTERNAL, the receiving
     * account).
     */
    public record TransactionResponse(
            UUID id, String type, String status, long amountMinorUnits, String currency,
            UUID sourceAccountId, UUID destinationAccountId, String description, OffsetDateTime createdAt,
            String direction, String counterparty, String sourceLabel, String destinationLabel) {

        static TransactionResponse from(TransactionView t) {
            return new TransactionResponse(t.id(), t.type(), t.status(), t.amount(), t.currency(),
                    t.sourceAccountId(), t.destinationAccountId(), t.description(), t.createdAt(),
                    null, null, null, null);
        }

        static TransactionResponse from(TransactionDetail d) {
            TransactionView t = d.transaction();
            String direction = d.sourceOwn() && d.destinationOwn() ? "INTERNAL"
                    : d.sourceOwn() ? "OUT" : "IN";
            String counterparty = "IN".equals(direction) ? d.sourceLabel() : d.destinationLabel();
            return new TransactionResponse(t.id(), t.type(), t.status(), t.amount(), t.currency(),
                    t.sourceAccountId(), t.destinationAccountId(), t.description(), t.createdAt(),
                    direction, counterparty, d.sourceLabel(), d.destinationLabel());
        }
    }

    public record PageResponse<T>(List<T> items, String nextCursor) {
    }

    public record StatementLineResponse(
            UUID entryId, UUID transactionId, long amountMinorUnits, String direction,
            long balanceAfterMinorUnits, String currency, OffsetDateTime createdAt,
            String type, String description, String counterparty) {
    }

    public record EntryResponse(UUID accountId, String account, String accountType, boolean own,
                                long amountMinorUnits, String direction) {
    }

    public record TransactionEntriesResponse(TransactionResponse transaction, List<EntryResponse> entries,
                                             long sumMinorUnits) {
    }

    @GetMapping("/transactions")
    @Operation(summary = "Your transactions, newest first, with optional filters",
            description = "Dates are whole days in the given time zone. q searches descriptions.")
    PageResponse<TransactionResponse> myTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false)
            @Pattern(regexp = "DEPOSIT|WITHDRAWAL|TRANSFER|PAYMENT|REFUND") String type,
            @RequestParam(required = false) @Pattern(regexp = "IN|OUT") String direction,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Min(0) Long minAmount,
            @RequestParam(required = false) @Min(0) Long maxAmount,
            @RequestParam(defaultValue = "UTC") String zone) {
        CurrentUser user = CurrentUser.from(jwt);
        if (accountId != null) {
            accountService.requireOwnedAccount(accountId, user.id(), user.isAdmin());
        }
        ZoneId zoneId = Zones.parse(zone);
        Filter filter = new Filter(type, accountId, direction,
                from == null ? null : from.atStartOfDay(zoneId).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime(),
                q == null || q.isBlank() ? null : q.strip(),
                minAmount, maxAmount);

        Cursor c = cursor == null ? null : Cursor.decode(cursor);
        List<TransactionDetail> page = transactions.search(user.id(), filter,
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);

        String nextCursor = null;
        if (page.size() == limit) {
            TransactionView last = page.getLast().transaction();
            nextCursor = new Cursor(last.createdAt(), last.id()).encode();
        }
        return new PageResponse<>(page.stream().map(TransactionResponse::from).toList(), nextCursor);
    }

    @GetMapping("/transactions/{id}")
    TransactionResponse byId(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        return TransactionResponse.from(requireVisible(id, user));
    }

    /**
     * The double-entry posting behind one transaction: every account it
     * touched and by how much. The amounts always add up to zero; showing
     * them lets anyone check that instead of taking it on trust.
     */
    @GetMapping("/transactions/{id}/entries")
    @Operation(summary = "The ledger entries behind a transaction")
    TransactionEntriesResponse entries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        TransactionView t = requireVisible(id, user);
        List<EntryView> entries = transactions.entriesOf(id, t.createdAt(), user.id());
        return new TransactionEntriesResponse(
                TransactionResponse.from(t),
                entries.stream()
                        .map(e -> new EntryResponse(e.own() ? e.accountId() : null, e.accountLabel(),
                                e.accountType(), e.own(), e.amount(), e.direction()))
                        .toList(),
                entries.stream().mapToLong(EntryView::amount).sum());
    }

    private TransactionView requireVisible(UUID id, CurrentUser user) {
        TransactionView t = transactions.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found"));
        if (!user.isAdmin() && !transactions.involvesUser(id, user.id())) {
            throw ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found");
        }
        return t;
    }

    /**
     * The first page (no cursor, default limit) is cache-aside in Redis
     * with a 30s TTL; money movements evict the touched accounts' keys
     * after commit. Cursored pages are immutable-ish history and cheap by
     * index, so they bypass the cache. X-Cache exposes HIT/MISS/BYPASS.
     */
    @GetMapping("/accounts/{accountId}/transactions")
    ResponseEntity<String> byAccount(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable UUID accountId,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit)
            throws JsonProcessingException {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.requireOwnedAccount(accountId, user.id(), user.isAdmin());

        boolean cacheable = cursor == null && limit == 50;
        if (cacheable) {
            String hit = cache.get(CacheKeys.accountHistory(accountId));
            if (hit != null) {
                return jsonResponse(hit, "HIT");
            }
        }
        Cursor c = cursor == null ? null : Cursor.decode(cursor);
        List<TransactionView> page = transactions.findForAccount(accountId,
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);
        String nextCursor = null;
        if (page.size() == limit) {
            TransactionView last = page.getLast();
            nextCursor = new Cursor(last.createdAt(), last.id()).encode();
        }
        String body = objectMapper.writeValueAsString(
                new PageResponse<>(page.stream().map(TransactionResponse::from).toList(), nextCursor));
        if (cacheable) {
            cache.put(CacheKeys.accountHistory(accountId), body, java.time.Duration.ofSeconds(30));
            return jsonResponse(body, "MISS");
        }
        return jsonResponse(body, "BYPASS");
    }

    private ResponseEntity<String> jsonResponse(String body, String cacheStatus) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cache", cacheStatus)
                .body(body);
    }

    /**
     * Account statement: ledger entries newest first with the balance after
     * each entry, computed with a window function from the current balance
     * (first page) or the cursor's carried balance (later pages).
     */
    @GetMapping("/accounts/{accountId}/statement")
    @Operation(summary = "Account statement with a running balance")
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

        List<StatementLine> lines = transactions.statement(accountId, user.id(), startBalance,
                c == null ? null : c.createdAt(), c == null ? null : c.id(), limit);

        String nextCursor = null;
        if (lines.size() == limit) {
            StatementLine last = lines.getLast();
            nextCursor = new StatementCursor(last.createdAt(), last.entryId(),
                    last.balanceAfter() - last.amount()).encode();
        }
        return new PageResponse<>(lines.stream()
                .map(l -> new StatementLineResponse(l.entryId(), l.transactionId(), l.amount(),
                        l.direction(), l.balanceAfter(), l.currency(), l.createdAt(),
                        l.type(), l.description(), l.counterparty()))
                .toList(), nextCursor);
    }

    /**
     * The same statement as a CSV file for a date range, oldest first, for
     * spreadsheets and accounting tools.
     */
    @GetMapping("/accounts/{accountId}/statement/export")
    @Operation(summary = "Download a statement as CSV",
            description = "from and to are whole days in the given time zone, at most 366 days apart.")
    ResponseEntity<byte[]> exportStatement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "UTC") String zone) {
        CurrentUser user = CurrentUser.from(jwt);
        StatementExport.Export export = statementExport.csv(
                accountId, user.id(), user.isAdmin(), from, to, Zones.parse(zone));
        // The byte order mark makes Excel read the file as UTF-8, so names
        // with accents survive the round trip.
        byte[] body = ("﻿" + export.csv()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
