package com.ledgerflow.organization;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations", schema = "ledgerflow")
class Organization {
  @Id private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Organization() {}

  Organization(String name, Instant now) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.createdAt = now;
  }

  UUID id() {
    return id;
  }

  String name() {
    return name;
  }
}
