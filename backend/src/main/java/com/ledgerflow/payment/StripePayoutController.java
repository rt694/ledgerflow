package com.ledgerflow.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/stripe-payouts")
public class StripePayoutController {
  private final StripePayoutService service;

  StripePayoutController(StripePayoutService service) {
    this.service = service;
  }

  @PostMapping("/import")
  StripePayoutService.Payout importPayout(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ImportRequest request) {
    return service.importPayout(organizationId, actor(jwt), request.providerPayoutId());
  }

  @GetMapping
  List<StripePayoutService.PayoutSummary> list(
      @PathVariable UUID organizationId, @AuthenticationPrincipal Jwt jwt) {
    return service.list(organizationId, actor(jwt));
  }

  @GetMapping("/{id}")
  StripePayoutService.Payout get(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.get(organizationId, actor(jwt), id);
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  @Schema(name = "StripePayoutImportRequest")
  public record ImportRequest(
      @NotBlank @Pattern(regexp = "po_[A-Za-z0-9]{1,252}") String providerPayoutId) {}
}
