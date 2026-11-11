package com.ledgerflow.identity.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.identity.domain.IdentityService;
import com.ledgerflow.identity.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Profile", description = "Your own details and password")
public class ProfileController {

    private final IdentityService identityService;

    public ProfileController(IdentityService identityService) {
        this.identityService = identityService;
    }

    public record ProfileResponse(UUID id, String email, String fullName, String role, OffsetDateTime createdAt) {
        static ProfileResponse from(User user) {
            return new ProfileResponse(user.id(), user.email(), user.fullName(), user.role(), user.createdAt());
        }
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 200) String fullName) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    @GetMapping
    @Operation(summary = "Who am I signed in as")
    ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return ProfileResponse.from(identityService.requireUser(CurrentUser.from(jwt).id()));
    }

    @PatchMapping
    @Operation(summary = "Change the name shown to people you pay")
    ProfileResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return ProfileResponse.from(
                identityService.updateName(CurrentUser.from(jwt).id(), request.fullName()));
    }

    @PostMapping("/password")
    @Operation(summary = "Change your password",
            description = "Needs the current password. Sessions already signed in stay valid until they expire.")
    ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        identityService.changePassword(CurrentUser.from(jwt).id(),
                request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
