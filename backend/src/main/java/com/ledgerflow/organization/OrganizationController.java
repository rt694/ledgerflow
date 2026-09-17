package com.ledgerflow.organization;

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
@RequestMapping("/api/v1/organizations")
public class OrganizationController {
  private final OrganizationService service;

  public OrganizationController(OrganizationService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<OrganizationResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
    var response = service.create(actor(jwt), request.name());
    return ResponseEntity.created(URI.create("/api/v1/organizations/" + response.id()))
        .body(response);
  }

  @GetMapping
  public PageResponse<OrganizationResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(actor(jwt), page, size);
  }

  @GetMapping("/{id}")
  public OrganizationResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.get(id, actor(jwt));
  }

  @GetMapping("/{id}/members")
  public PageResponse<MemberResponse> members(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.members(id, actor(jwt), page, size);
  }

  @PostMapping("/{id}/members")
  public ResponseEntity<MemberResponse> addMember(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody AddMemberRequest request) {
    var member = service.addMember(id, actor(jwt), request.email(), request.role());
    return ResponseEntity.created(
            URI.create("/api/v1/organizations/" + id + "/members/" + member.userId()))
        .body(member);
  }

  @PutMapping("/{id}/members/{userId}")
  public MemberResponse changeRole(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody RoleRequest request) {
    return service.changeRole(id, actor(jwt), userId, request.role());
  }

  @DeleteMapping("/{id}/members/{userId}")
  public ResponseEntity<Void> removeMember(
      @PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    service.removeMember(id, actor(jwt), userId);
    return ResponseEntity.noContent().build();
  }

  private UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  @io.swagger.v3.oas.annotations.media.Schema(name = "OrganizationCreateRequest")
  public record CreateRequest(@NotBlank @Size(max = 200) String name) {}

  public record AddMemberRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotNull Role role) {}

  public record RoleRequest(@NotNull Role role) {}

  public record OrganizationResponse(UUID id, String name, Role role) {}

  public record MemberResponse(UUID userId, String email, String displayName, Role role) {}
}
