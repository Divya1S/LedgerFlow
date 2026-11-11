package com.ledgerflow.account.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.RecipientService;
import com.ledgerflow.account.persistence.RecipientRepository.RecipientRow;
import com.ledgerflow.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/recipients")
@Tag(name = "Recipients", description = "People and businesses you send money to")
public class RecipientController {

    private final RecipientService recipientService;

    public RecipientController(RecipientService recipientService) {
        this.recipientService = recipientService;
    }

    public record AddRecipientRequest(@NotNull UUID accountId, @Size(max = 80) String nickname) {
    }

    public record RecipientResponse(UUID id, UUID accountId, String nickname, String accountType,
                                    String accountStatus, String currency, String displayName,
                                    String category, OffsetDateTime createdAt) {
        static RecipientResponse from(RecipientRow r) {
            return new RecipientResponse(r.id(), r.accountId(), r.nickname(), r.accountType(),
                    r.accountStatus(), r.currency(), r.displayName(), r.category(), r.createdAt());
        }
    }

    @GetMapping
    @Operation(summary = "List your saved recipients")
    List<RecipientResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return recipientService.list(CurrentUser.from(jwt).id()).stream().map(RecipientResponse::from).toList();
    }

    @PostMapping
    @Operation(summary = "Save an account as a recipient")
    ResponseEntity<RecipientResponse> add(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody AddRecipientRequest request) {
        RecipientRow row = recipientService.add(CurrentUser.from(jwt).id(), request.accountId(), request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecipientResponse.from(row));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a saved recipient")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        recipientService.remove(CurrentUser.from(jwt).id(), id);
        return ResponseEntity.noContent().build();
    }
}
