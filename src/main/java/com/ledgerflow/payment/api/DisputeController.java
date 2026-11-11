package com.ledgerflow.payment.api;

import java.util.List;
import java.util.UUID;

import com.ledgerflow.common.idempotency.IdempotentEndpoint;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.payment.domain.DisputeService;
import com.ledgerflow.payment.domain.DisputeService.DisputeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1")
@Tag(name = "Disputes", description = "Challenge a payment and resolve the challenge")
public class DisputeController {

    private final DisputeService disputeService;
    private final IdempotentEndpoint idempotent;

    public DisputeController(DisputeService disputeService, IdempotentEndpoint idempotent) {
        this.disputeService = disputeService;
        this.idempotent = idempotent;
    }

    public record OpenDisputeRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "REFUND|DECLINE") String outcome,
            @Size(max = 1000) String note) {
    }

    @PostMapping("/payments/{paymentId}/disputes")
    @Operation(summary = "Dispute a payment you made")
    ResponseEntity<DisputeView> open(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable UUID paymentId,
                                     @Valid @RequestBody OpenDisputeRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.open(user.id(), paymentId, request.reason()));
    }

    @GetMapping("/disputes")
    @Operation(summary = "Disputes you opened or received")
    List<DisputeView> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return disputeService.list(user.id(), user.isAdmin());
    }

    @PostMapping("/disputes/{disputeId}/decision")
    @Operation(summary = "Refund or decline a dispute",
            description = "REFUND returns the remaining amount to the customer. A merchant's "
                    + "DECLINE sends the dispute to the platform for a final decision.")
    ResponseEntity<String> decide(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID disputeId,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                  @Valid @RequestBody DecisionRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/disputes/" + disputeId + "/decision", idemKey, request, 200,
                keyId -> disputeService.decide(user.id(), user.isAdmin(), disputeId,
                        request.outcome(), request.note(), keyId));
    }
}
