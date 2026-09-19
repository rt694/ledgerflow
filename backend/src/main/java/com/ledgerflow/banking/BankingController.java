package com.ledgerflow.banking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/banking")
public class BankingController {
  private final BankingService service;

  public BankingController(BankingService service) {
    this.service = service;
  }

  @PostMapping("/link-token")
  public ResponseEntity<LinkTokenResponse> linkToken(
      @PathVariable UUID organizationId, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new LinkTokenResponse(service.linkToken(organizationId, actor(jwt))));
  }

  @PostMapping("/connections")
  public ResponseEntity<BankingService.Connection> exchange(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String key,
      @Valid @RequestBody ExchangeRequest request) {
    return ResponseEntity.status(201)
        .body(service.exchange(organizationId, actor(jwt), request.publicToken(), key));
  }

  @GetMapping("/connections")
  public List<BankingService.Connection> connections(
      @PathVariable UUID organizationId, @AuthenticationPrincipal Jwt jwt) {
    return service.connections(organizationId, actor(jwt));
  }

  @GetMapping("/connections/{id}")
  public BankingService.Connection connection(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.get(organizationId, actor(jwt), id);
  }

  @PostMapping("/connections/{id}/sync")
  public BankingService.Connection sync(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.sync(organizationId, actor(jwt), id);
  }

  @GetMapping("/connections/{id}/accounts")
  public List<BankingService.Account> accounts(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.accounts(organizationId, actor(jwt), id);
  }

  @GetMapping("/transactions")
  public List<BankingService.BankTransaction> transactions(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "false") boolean includeRemoved) {
    return service.transactions(organizationId, actor(jwt), includeRemoved);
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record LinkTokenResponse(String linkToken) {}

  public record ExchangeRequest(@NotBlank @Size(max = 256) String publicToken) {}
}
