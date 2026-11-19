package com.ledgerflow.transfer.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.common.idempotency.IdempotentEndpoint;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transfer.domain.TransferHoldService;
import com.ledgerflow.transfer.domain.TransferHoldService.Holds;
import com.ledgerflow.transfer.domain.TransferProtection;
import com.ledgerflow.transfer.domain.TransferProtection.Assessment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Held transfers", description = "First payments to someone new wait on hold and can be cancelled")
public class TransferHoldController {

    private final TransferHoldService holds;
    private final TransferProtection protection;
    private final IdempotentEndpoint idempotent;

    public TransferHoldController(TransferHoldService holds, TransferProtection protection,
                                  IdempotentEndpoint idempotent) {
        this.holds = holds;
        this.protection = protection;
        this.idempotent = idempotent;
    }

    /** releaseAt is when the money would arrive if the transfer were sent now. */
    public record ProtectionCheck(boolean held, String riskLevel, List<String> reasons, int holdMinutes,
                                  OffsetDateTime releaseAt, long thresholdMinorUnits) {
    }

    /**
     * Tells the sender BEFORE they send whether the transfer will wait on
     * hold, for how long and why. The delay is never a surprise after the
     * fact: the arrival time is on the screen when they confirm.
     */
    @GetMapping("/transfers/protection-check")
    @Operation(summary = "Will this transfer be held, for how long and why",
            description = "Read-only. Uses the same rule the transfer itself will use.")
    ProtectionCheck check(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam UUID destinationAccountId,
                          @RequestParam @Min(1) @Max(1_000_000_000_000L) long amountMinorUnits,
                          @RequestParam(defaultValue = "USD") String currency) {
        CurrentUser user = CurrentUser.from(jwt);
        Assessment a = protection.assess(user.id(), destinationAccountId, amountMinorUnits, currency);
        return new ProtectionCheck(a.held(), a.riskLevel(), a.reasons(), a.holdMinutes(),
                a.held() ? OffsetDateTime.now().plusMinutes(a.holdMinutes()) : null,
                protection.thresholdMinorUnits());
    }

    @GetMapping("/transfer-holds")
    @Operation(summary = "Transfers you sent that are (or were) on hold, and money on its way to you")
    Holds list(@AuthenticationPrincipal Jwt jwt) {
        return holds.list(CurrentUser.from(jwt).id());
    }

    @PostMapping("/transfer-holds/{id}/cancel")
    @Operation(summary = "Cancel a transfer that is still on hold",
            description = "The money returns to the wallet it was sent from. Only the sender can cancel.")
    ResponseEntity<String> cancel(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID id,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idemKey) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/transfer-holds/" + id + "/cancel", idemKey, Map.of(), 200,
                keyId -> holds.cancel(user.id(), id, keyId));
    }
}
