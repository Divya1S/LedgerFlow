package com.ledgerflow.transfer.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transfer.domain.ScheduledTransferService;
import com.ledgerflow.transfer.domain.ScheduledTransferService.ScheduleView;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scheduled-transfers")
@Tag(name = "Scheduled transfers", description = "Transfers that run on a date or on a repeat")
public class ScheduledTransferController {

    private final ScheduledTransferService service;

    public ScheduledTransferController(ScheduledTransferService service) {
        this.service = service;
    }

    public record CreateScheduleRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Min(1) @Max(1_000_000_000_000L) long amountMinorUnits,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "ONCE|WEEKLY|MONTHLY") String frequency,
            @NotNull LocalDate startDate,
            @Size(max = 64) String zone) {
    }

    @PostMapping
    @Operation(summary = "Schedule a transfer",
            description = "Runs once on startDate, or every week or month from that date.")
    ResponseEntity<ScheduleView> create(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody CreateScheduleRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        ScheduleView view = service.create(user.id(), request.sourceAccountId(),
                request.destinationAccountId(), request.amountMinorUnits(), request.currency(),
                request.description(), request.frequency(), request.startDate(), zone(request.zone()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    @Operation(summary = "List your scheduled transfers")
    List<ScheduleView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentUser.from(jwt).id());
    }

    @PostMapping("/{id}/pause")
    ScheduleView pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.pause(CurrentUser.from(jwt).id(), id);
    }

    @PostMapping("/{id}/resume")
    ScheduleView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.resume(CurrentUser.from(jwt).id(), id);
    }

    @PostMapping("/{id}/cancel")
    ScheduleView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(CurrentUser.from(jwt).id(), id);
    }

    private static ZoneId zone(String zone) {
        if (zone == null || zone.isBlank()) {
            return ZoneId.of("UTC");
        }
        if (!ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ZONE",
                    "zone must be a region name such as UTC or Europe/Paris");
        }
        return ZoneId.of(zone);
    }
}
