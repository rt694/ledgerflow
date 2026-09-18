package com.ledgerflow.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/payments")
public class PaymentController {
  private final PaymentService service;

  public PaymentController(PaymentService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<PaymentService.IntentResponse> create(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String key,
      @Valid @RequestBody CreateRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.create(organizationId, actor(jwt), request.invoiceId(), key));
  }

  @GetMapping("/{id}")
  public PaymentResponse get(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return response(service.get(organizationId, actor(jwt), id));
  }

  @PostMapping("/{id}/cancel")
  public PaymentResponse cancel(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return response(service.cancel(organizationId, actor(jwt), id));
  }

  @PostMapping("/{id}/refunds")
  public ResponseEntity<PaymentService.RefundRequest> refund(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String key) {
    return ResponseEntity.accepted().body(service.refund(organizationId, actor(jwt), id, key));
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private PaymentResponse response(PaymentService.Payment p) {
    return new PaymentResponse(
        p.id(), p.invoiceId(), p.amount(), p.currency(), p.providerId(), p.state(), p.createdAt());
  }

  @Schema(name = "PaymentCreateRequest")
  public record CreateRequest(@NotNull UUID invoiceId) {}

  public record PaymentResponse(
      UUID id,
      UUID invoiceId,
      BigDecimal amount,
      String currency,
      String providerId,
      String state,
      Instant createdAt) {}
}
