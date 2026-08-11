package com.ledgerflow.account.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.Balance;
import com.ledgerflow.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record OpenAccountRequest(
            @NotBlank @Pattern(regexp = "USER_WALLET|MERCHANT") String type,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 120) String name) {
    }

    public record AccountResponse(UUID id, String type, String currency, String status,
                                  String name, OffsetDateTime createdAt) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.id(), a.type(), a.currency(), a.status(), a.name(), a.createdAt());
        }
    }

    public record BalanceResponse(UUID accountId, long balanceMinorUnits, String currency, OffsetDateTime asOf) {
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody OpenAccountRequest request,
                                         UriComponentsBuilder uri) {
        CurrentUser user = CurrentUser.from(jwt);
        Account account = accountService.openAccount(user.id(), request.type(), request.currency(), request.name());
        return ResponseEntity
                .created(uri.path("/api/v1/accounts/{id}").buildAndExpand(account.id()).toUri())
                .body(AccountResponse.from(account));
    }

    @GetMapping
    List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return accountService.listOwnAccounts(user.id()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        return AccountResponse.from(accountService.requireOwnedAccount(id, user.id(), user.isAdmin()));
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        Balance b = accountService.balanceOf(id, user.id(), user.isAdmin());
        return new BalanceResponse(b.accountId(), b.balance(), b.currency(), b.asOf());
    }

    @DeleteMapping("/{id}")
    AccountResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser user = CurrentUser.from(jwt);
        return AccountResponse.from(accountService.closeAccount(id, user.id(), user.isAdmin()));
    }
}
