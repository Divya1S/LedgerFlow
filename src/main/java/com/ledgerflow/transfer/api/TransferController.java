package com.ledgerflow.transfer.api;

import java.util.UUID;

import com.ledgerflow.common.idempotency.IdempotentEndpoint;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transfer.domain.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    private final TransferService transferService;
    private final IdempotentEndpoint idempotent;

    public TransferController(TransferService transferService, IdempotentEndpoint idempotent) {
        this.transferService = transferService;
        this.idempotent = idempotent;
    }

    public record TransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Min(1) @Max(1_000_000_000_000L) long amountMinorUnits,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description) {
    }

    public record FundingRequest(
            @Min(1) @Max(1_000_000_000_000L) long amountMinorUnits,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description) {
    }

    @PostMapping("/transfers")
    ResponseEntity<String> transfer(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                    @Valid @RequestBody TransferRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/transfers", idemKey, request, 201,
                keyId -> transferService.transfer(user.id(), request.sourceAccountId(),
                        request.destinationAccountId(), request.amountMinorUnits(),
                        request.currency(), request.description(), keyId));
    }

    @PostMapping("/accounts/{accountId}/deposits")
    ResponseEntity<String> deposit(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable UUID accountId,
                                   @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                   @Valid @RequestBody FundingRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/accounts/" + accountId + "/deposits", idemKey, request, 201,
                keyId -> transferService.deposit(user.id(), accountId, request.amountMinorUnits(),
                        request.currency(), request.description(), keyId));
    }

    @PostMapping("/accounts/{accountId}/withdrawals")
    ResponseEntity<String> withdraw(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID accountId,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                    @Valid @RequestBody FundingRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/accounts/" + accountId + "/withdrawals", idemKey, request, 201,
                keyId -> transferService.withdraw(user.id(), accountId, request.amountMinorUnits(),
                        request.currency(), request.description(), keyId));
    }
}
