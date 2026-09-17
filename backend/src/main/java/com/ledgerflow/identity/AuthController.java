package com.ledgerflow.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final UserDirectory users;

  public AuthController(AuthService auth, UserDirectory users) {
    this.auth = auth;
    this.users = users;
  }

  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  @PostMapping("/register")
  public ResponseEntity<UserDirectory.UserSummary> register(
      @Valid @RequestBody RegisterRequest request) {
    var user = auth.register(request);
    return ResponseEntity.created(URI.create("/api/v1/auth/me")).body(user);
  }

  @io.swagger.v3.oas.annotations.security.SecurityRequirements
  @PostMapping("/login")
  public TokenService.TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return auth.login(request);
  }

  @GetMapping("/me")
  public UserDirectory.UserSummary me(@AuthenticationPrincipal Jwt jwt) {
    return users.require(UUID.fromString(jwt.getSubject()));
  }

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 100) String displayName,
      @NotBlank @Size(min = 12, max = 72) String password) {}

  public record LoginRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
}
