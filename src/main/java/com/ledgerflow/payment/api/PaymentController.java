package com.ledgerflow.payment.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.idempotency.IdempotentEndpoint;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.common.web.PageCursor;
import com.ledgerflow.fraud.domain.FraudAssessments;
import com.ledgerflow.payment.domain.PaymentService;
import com.ledgerflow.payment.domain.PaymentService.PaymentListItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Pay a merchant, refund, and review fraud checks")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotentEndpoint idempotent;
    private final FraudAssessments fraudAssessments;

    public PaymentController(PaymentService paymentService, IdempotentEndpoint idempotent,
                             FraudAssessments fraudAssessments) {
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

    /** fraudVerdict is filled in for the merchant's view only. */
    public record PaymentListResponse(UUID paymentId, UUID transactionId, String status,
                                      long amountMinorUnits, long feeMinorUnits, long refundedMinorUnits,
                                      String currency, String description, String payerName,
                                      String merchantName, String merchantCategory, String role,
                                      String fraudVerdict, UUID disputeId, String disputeStatus,
                                      OffsetDateTime createdAt) {
    }

    public record PaymentPage(List<PaymentListResponse> items, String nextCursor) {
    }

    @PostMapping
    @Operation(summary = "Pay a merchant from one of your wallets")
    ResponseEntity<String> pay(@AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
                               @Valid @RequestBody PaymentRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        return idempotent.run(user, "POST /api/v1/payments", idemKey, request, 201,
                keyId -> paymentService.pay(user.id(), request.sourceAccountId(),
                        request.destinationAccountId(), request.amountMinorUnits(),
                        request.currency(), request.description(), keyId));
    }

    @GetMapping
    @Operation(summary = "Payments you made (role=PAYER) or received (role=MERCHANT)")
    PaymentPage list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(defaultValue = "PAYER") @Pattern(regexp = "PAYER|MERCHANT") String role,
                     @RequestParam(required = false) String cursor,
                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        CurrentUser user = CurrentUser.from(jwt);
        boolean asMerchant = "MERCHANT".equals(role);
        PageCursor c = PageCursor.decodeOrNull(cursor);
        List<PaymentListItem> page = paymentService.list(user.id(), asMerchant,
                c == null ? null : c.time(), c == null ? null : c.id(), limit);

        // Fraud reasoning is the merchant's to see, never the payer's.
        Map<UUID, String> verdicts = asMerchant
                ? fraudAssessments.verdictsFor(page.stream().map(PaymentListItem::paymentId).toList())
                : Map.of();

        String nextCursor = null;
        if (page.size() == limit) {
            PaymentListItem last = page.getLast();
            nextCursor = new PageCursor(last.createdAt(), last.paymentId()).encode();
        }
        return new PaymentPage(page.stream()
                .map(p -> new PaymentListResponse(p.paymentId(), p.transactionId(), p.status(),
                        p.amountMinorUnits(), p.feeMinorUnits(), p.refundedMinorUnits(), p.currency(),
                        p.description(), p.payerName(), p.merchantName(), p.merchantCategory(), p.role(),
                        verdicts.get(p.paymentId()), p.disputeId(), p.disputeStatus(), p.createdAt()))
                .toList(), nextCursor);
    }

    @PostMapping("/{paymentId}/refunds")
    @Operation(summary = "Refund a payment, in full or in part (merchant only)")
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
    @Operation(summary = "Fraud check result for a payment you received")
    ResponseEntity<String> fraudAssessment(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID paymentId) {
        CurrentUser user = CurrentUser.from(jwt);
        paymentService.requireMerchantOrAdmin(user.id(), user.isAdmin(), paymentId);
        var assessment = fraudAssessments.forPayment(paymentId)
                .orElseThrow(() -> ApiException.notFound(
                        "ASSESSMENT_NOT_FOUND", "No fraud decision exists for this payment yet"));
        String body = """
                {"verdict":"%s","score":%d,"ruleHits":%s,"aiAssessment":%s,"aiModel":%s,"aiAssessedAt":%s}
                """.formatted(
                assessment.verdict(), assessment.score(), assessment.ruleHitsJson(),
                assessment.aiAssessmentJson() == null ? "null" : assessment.aiAssessmentJson(),
                assessment.aiModel() == null ? "null" : "\"" + assessment.aiModel() + "\"",
                assessment.aiAssessedAt() == null ? "null" : "\"" + assessment.aiAssessedAt() + "\"");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.strip());
    }
}
