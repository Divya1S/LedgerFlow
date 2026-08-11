package com.ledgerflow.identity.api;

import java.util.UUID;

import com.ledgerflow.identity.domain.IdentityService;
import com.ledgerflow.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IdentityService identityService;

    public AuthController(IdentityService identityService) {
        this.identityService = identityService;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 200) String fullName) {
    }

    public record RegisterResponse(UUID id, String email, String fullName, String role) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, UUID userId) {
    }

    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@jakarta.validation.Valid @RequestBody RegisterRequest request,
                                              UriComponentsBuilder uri) {
        User user = identityService.register(request.email(), request.password(), request.fullName());
        return ResponseEntity
                .created(uri.path("/api/v1/users/{id}").buildAndExpand(user.id()).toUri())
                .body(new RegisterResponse(user.id(), user.email(), user.fullName(), user.role()));
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@jakarta.validation.Valid @RequestBody LoginRequest request) {
        IdentityService.AuthResult result = identityService.login(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new LoginResponse(result.token(), "Bearer", result.user().id()));
    }
}
