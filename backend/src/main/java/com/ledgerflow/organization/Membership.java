package com.ledgerflow.organization;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memberships", schema = "ledgerflow")
class Membership {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Membership() {}

  Membership(UUID organizationId, UUID userId, Role role, Instant now) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.userId = userId;
    this.role = role;
    this.createdAt = now;
  }

  UUID userId() {
    return userId;
  }

  Role role() {
    return role;
  }

  void changeRole(Role role) {
    this.role = role;
  }
}
