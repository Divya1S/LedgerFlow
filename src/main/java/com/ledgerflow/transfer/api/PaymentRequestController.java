package com.ledgerflow.transfer.api;

import java.util.List;
import java.util.UUID;

import com.ledgerflow.common.idempotency.IdempotentEndpoint;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transfer.domain.PaymentRequestService;
import com.ledgerflow.transfer.domain.PaymentRequestService.RequestView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-requests")
@Tag(name = "Money requests", description = "Ask someone to pay you with a shareable link")
public class PaymentRequestController {

    private final PaymentRequestService service;
    private final IdempotentEndpoint idempotent;

    public PaymentRequestController(PaymentRequestService service, IdempotentEndpoint idempotent) {
        this.service = service;
        this.idempotent = idempotent;
    }

    public record CreateRequest(
            @NotNull UUID destinationAccountId,
            @Min(1) @Max(1_000_000_000_000L) long amountMinorUnits,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 280) String note) {
    }

    public record PayRequest(@NotNull UUID sourceAccountId) {
    }

    @PostMapping
    @Operation(summary = "Ask for money", description = "Returns a request whose id can be shared as a link.")
    ResponseEntity<RequestView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.id(),
                request.destinationAccountId(), request.amountMinorUnits(), request.currency(), request.note()));
    }

    @GetMapping
    @Operation(summary = "Money requests you created")
    List<RequestView> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.listMine(CurrentUser.from(jwt).id());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Look at a money request before paying it")
    RequestView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(CurrentUser.from(jwt).id(), id);
    }

    @PostMapping("/{id}/cancel")
    RequestView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(CurrentUser.from(jwt).id(), id);
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Pay a money request from one of your accounts")
    ResponseEntity<String> pay(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable UUID id,
                               @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                               @Valid @RequestBody PayRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/payment-requests/" + id + "/pay", idemKey, request, 201,
                keyId -> service.pay(user.id(), id, request.sourceAccountId(), keyId));
    }
}
