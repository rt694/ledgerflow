package com.ledgerflow.invoicing;

import com.ledgerflow.invoicing.InvoiceCalculator.LineInput;
import com.ledgerflow.shared.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/invoices")
public class InvoiceController {
  private final InvoiceService service;

  public InvoiceController(InvoiceService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<InvoiceResponse> create(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreateRequest request) {
    var result = service.create(organizationId, actor(jwt), request);
    return ResponseEntity.created(
            URI.create("/api/v1/organizations/" + organizationId + "/invoices/" + result.id()))
        .body(result);
  }

  @GetMapping
  public PageResponse<InvoiceResponse> list(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(organizationId, actor(jwt), page, size);
  }

  @GetMapping("/{id}")
  public InvoiceResponse get(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.get(organizationId, actor(jwt), id);
  }

  @PutMapping("/{id}")
  public InvoiceResponse update(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody UpdateRequest request) {
    return service.update(organizationId, actor(jwt), id, request);
  }

  @PostMapping("/{id}/issue")
  public InvoiceResponse issue(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody VersionRequest request) {
    return service.issue(organizationId, actor(jwt), id, request.version());
  }

  @PostMapping("/{id}/void")
  public InvoiceResponse voidInvoice(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody VersionRequest request) {
    return service.voidInvoice(organizationId, actor(jwt), id, request.version());
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  @io.swagger.v3.oas.annotations.media.Schema(name = "InvoiceCreateRequest")
  public record CreateRequest(
      @NotNull UUID customerId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String number,
      @NotNull CurrencyCode currency,
      @NotNull LocalDate dueDate,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid LineInput> lines) {}

  @io.swagger.v3.oas.annotations.media.Schema(name = "InvoiceUpdateRequest")
  public record UpdateRequest(
      @NotNull UUID customerId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String number,
      @NotNull CurrencyCode currency,
      @NotNull LocalDate dueDate,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid LineInput> lines,
      @NotNull @PositiveOrZero Long version) {}

  public record VersionRequest(@NotNull @PositiveOrZero Long version) {}

  public record LineResponse(
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      BigDecimal subtotal,
      BigDecimal tax,
      BigDecimal total) {}

  public record InvoiceResponse(
      UUID id,
      UUID customerId,
      String customerName,
      String customerEmail,
      String number,
      CurrencyCode currency,
      InvoiceStatus status,
      LocalDate dueDate,
      BigDecimal subtotal,
      BigDecimal tax,
      BigDecimal total,
      long version,
      Instant issuedAt,
      Instant voidedAt,
      List<LineResponse> lines) {}
}
