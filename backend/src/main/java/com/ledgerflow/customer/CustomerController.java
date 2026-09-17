package com.ledgerflow.customer;

import com.ledgerflow.shared.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/customers")
public class CustomerController {
  private final CustomerService service;

  public CustomerController(CustomerService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<CustomerResponse> create(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CreateRequest request) {
    var response = service.create(organizationId, actor(jwt), request);
    return ResponseEntity.created(
            URI.create("/api/v1/organizations/" + organizationId + "/customers/" + response.id()))
        .body(response);
  }

  @GetMapping
  public PageResponse<CustomerResponse> list(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(organizationId, actor(jwt), page, size);
  }

  @GetMapping("/{id}")
  public CustomerResponse get(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.get(organizationId, actor(jwt), id);
  }

  @PutMapping("/{id}")
  public CustomerResponse update(
      @PathVariable UUID organizationId,
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody UpdateRequest request) {
    return service.update(organizationId, actor(jwt), id, request);
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  @io.swagger.v3.oas.annotations.media.Schema(name = "CustomerCreateRequest")
  public record CreateRequest(
      @NotBlank @Size(max = 200) String name, @NotBlank @Email @Size(max = 254) String email) {}

  @io.swagger.v3.oas.annotations.media.Schema(name = "CustomerUpdateRequest")
  public record UpdateRequest(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @PositiveOrZero Long version) {}

  public record CustomerResponse(UUID id, String name, String email, long version) {}
}
