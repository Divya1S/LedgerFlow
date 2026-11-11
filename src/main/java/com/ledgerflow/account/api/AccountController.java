package com.ledgerflow.account.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.AccountSummary;
import com.ledgerflow.account.domain.Balance;
import com.ledgerflow.account.domain.Payee;
import com.ledgerflow.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Wallets and merchant accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record OpenAccountRequest(
            @NotBlank @Pattern(regexp = "USER_WALLET|MERCHANT") String type,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 120) String name,
            @Pattern(regexp = "[A-Z]{3,20}") String category) {
    }

    public record RenameRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record AccountResponse(UUID id, String type, String currency, String status,
                                  String name, String category, long balanceMinorUnits,
                                  OffsetDateTime createdAt) {
        static AccountResponse from(Account a, long balance) {
            return new AccountResponse(a.id(), a.type(), a.currency(), a.status(), a.name(),
                    a.category(), balance, a.createdAt());
        }

        static AccountResponse from(AccountSummary s) {
            return from(s.account(), s.balance());
        }
    }

    public record BalanceResponse(UUID accountId, long balanceMinorUnits, String currency, OffsetDateTime asOf) {
    }

    public record PayeeResponse(UUID accountId, String type, String status, String currency,
                                String displayName, String category, boolean own) {
        static PayeeResponse from(Payee p) {
            return new PayeeResponse(p.accountId(), p.type(), p.status(), p.currency(),
                    p.displayName(), p.category(), p.own());
        }
    }

    @PostMapping
    @Operation(summary = "Open a wallet or a merchant account")
    ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody OpenAccountRequest request,
                                         UriComponentsBuilder uri) {
        CurrentUser user = CurrentUser.from(jwt);
        Account account = accountService.openAccount(user.id(), request.type(), request.currency(),
                request.name(), request.category());
        return ResponseEntity
                .created(uri.path("/api/v1/accounts/{id}").buildAndExpand(account.id()).toUri())
                .body(AccountResponse.from(account, 0));
    }

    @GetMapping
    @Operation(summary = "List your accounts with their balances")
    List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return accountService.listOwnAccountSummaries(user.id()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        return AccountResponse.from(accountService.summaryOf(id, user.id(), user.isAdmin()));
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        Balance b = accountService.balanceOf(id, user.id(), user.isAdmin());
        return new BalanceResponse(b.accountId(), b.balance(), b.currency(), b.asOf());
    }

    @GetMapping("/lookup/{id}")
    @Operation(summary = "Check who an account id belongs to before sending money",
            description = "Returns a display name only. No balance and no contact details.")
    PayeeResponse lookup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        return PayeeResponse.from(accountService.lookupPayee(id, user.id()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename an account")
    AccountResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                           @Valid @RequestBody RenameRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.rename(id, user.id(), user.isAdmin(), request.name());
        return AccountResponse.from(accountService.summaryOf(id, user.id(), user.isAdmin()));
    }

    @PostMapping("/{id}/freeze")
    @Operation(summary = "Freeze an account",
            description = "Money can still arrive, but nothing can leave until it is unfrozen.")
    AccountResponse freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.freeze(id, user.id(), user.isAdmin());
        return AccountResponse.from(accountService.summaryOf(id, user.id(), user.isAdmin()));
    }

    @PostMapping("/{id}/unfreeze")
    @Operation(summary = "Unfreeze an account")
    AccountResponse unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.unfreeze(id, user.id(), user.isAdmin());
        return AccountResponse.from(accountService.summaryOf(id, user.id(), user.isAdmin()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Close an account", description = "The balance must be zero first.")
    AccountResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        accountService.closeAccount(id, user.id(), user.isAdmin());
        return AccountResponse.from(accountService.summaryOf(id, user.id(), user.isAdmin()));
    }
}
