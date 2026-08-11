package com.ledgerflow.payment.api;

import java.util.UUID;

import com.ledgerflow.common.idempotency.IdempotentEndpoint;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.payment.domain.PaymentService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotentEndpoint idempotent;
    private final com.ledgerflow.fraud.domain.FraudAssessments fraudAssessments;

    public PaymentController(PaymentService paymentService, IdempotentEndpoint idempotent,
                             com.ledgerflow.fraud.domain.FraudAssessments fraudAssessments) {
        this.paymentService = paymentService;
        this.idempotent = idempotent;
        this.fraudAssessments = fraudAssessments;
    }

    public record PaymentRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Min(1) @Max(1_000_000_000_000L) long amountMinorUnits,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description) {
    }

    public record RefundRequest(
            @Min(1) @Max(1_000_000_000_000L) Long amountMinorUnits,
            @Size(max = 500) String reason) {
    }

    @PostMapping
    ResponseEntity<String> pay(@AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                               @Valid @RequestBody PaymentRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/payments", idemKey, request, 201,
                keyId -> paymentService.pay(user.id(), request.sourceAccountId(),
                        request.destinationAccountId(), request.amountMinorUnits(),
                        request.currency(), request.description(), keyId));
    }

    @PostMapping("/{paymentId}/refunds")
    ResponseEntity<String> refund(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID paymentId,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                                  @Valid @RequestBody RefundRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/payments/" + paymentId + "/refunds", idemKey, request, 201,
                keyId -> paymentService.refund(user.id(), user.isAdmin(), paymentId,
                        request.amountMinorUnits(), request.reason(), keyId));
    }

    @GetMapping("/{paymentId}")
    PaymentService.PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        CurrentUser user = CurrentUser.from(jwt);
        return paymentService.getPayment(user.id(), user.isAdmin(), paymentId);
    }

    /**
     * Fraud verdict plus the AI analyst's assessment (when one exists).
     * Merchant-or-admin only: fraud reasoning is not shown to the payer.
     */
    @GetMapping("/{paymentId}/fraud-assessment")
    org.springframework.http.ResponseEntity<String> fraudAssessment(@AuthenticationPrincipal Jwt jwt,
                                                                    @PathVariable UUID paymentId) {
        CurrentUser user = CurrentUser.from(jwt);
        paymentService.requireMerchantOrAdmin(user.id(), user.isAdmin(), paymentId);
        var assessment = fraudAssessments.forPayment(paymentId)
                .orElseThrow(() -> com.ledgerflow.common.error.ApiException.notFound(
                        "ASSESSMENT_NOT_FOUND", "No fraud decision exists for this payment yet"));
        String body = """
                {"verdict":"%s","score":%d,"ruleHits":%s,"aiAssessment":%s,"aiModel":%s,"aiAssessedAt":%s}
                """.formatted(
                assessment.verdict(), assessment.score(), assessment.ruleHitsJson(),
                assessment.aiAssessmentJson() == null ? "null" : assessment.aiAssessmentJson(),
                assessment.aiModel() == null ? "null" : "\"" + assessment.aiModel() + "\"",
                assessment.aiAssessedAt() == null ? "null" : "\"" + assessment.aiAssessedAt() + "\"");
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body.strip());
    }
}
