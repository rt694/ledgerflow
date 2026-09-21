package com.ledgerflow.reconciliation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/reconciliation")
public class ReconciliationController {
  private final ReconciliationService service;

  public ReconciliationController(ReconciliationService service) {
    this.service = service;
  }

  @PostMapping("/refresh")
  public ReconciliationService.RefreshResult refresh(
      @PathVariable UUID organizationId, @AuthenticationPrincipal Jwt jwt) {
    return service.refresh(organizationId, actor(jwt));
  }

  @GetMapping("/cases")
  public List<ReconciliationService.ReconciliationCase> cases(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String status) {
    return service.list(organizationId, actor(jwt), status);
  }

  @GetMapping("/cases/{id}")
  public ReconciliationService.Detail get(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.get(organizationId, actor(jwt), id);
  }

  @PostMapping("/cases/{id}/match")
  public ReconciliationService.Detail match(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody MatchRequest request) {
    return service.match(organizationId, actor(jwt), id, request.invoiceId(), request.version());
  }

  @PostMapping("/cases/{id}/match-payout")
  public ReconciliationService.Detail matchPayout(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody PayoutMatchRequest request) {
    return service.matchPayout(
        organizationId, actor(jwt), id, request.payoutId(), request.version());
  }

  @PostMapping("/cases/{id}/ignore")
  public ReconciliationService.Detail ignore(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody VersionRequest request) {
    return service.ignore(organizationId, actor(jwt), id, request.version());
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  public record MatchRequest(@NotNull UUID invoiceId, @NotNull @Min(0) Long version) {}

  @Schema(name = "PayoutMatchRequest")
  public record PayoutMatchRequest(@NotNull UUID payoutId, @NotNull @Min(0) Long version) {}

  public record VersionRequest(@NotNull @Min(0) Long version) {}
}
