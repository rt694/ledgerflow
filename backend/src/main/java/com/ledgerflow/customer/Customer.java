package com.ledgerflow.customer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers", schema = "ledgerflow")
class Customer {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(nullable = false, length = 254)
  private String email;

  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Customer() {}

  Customer(UUID org, String name, String email, Instant now) {
    id = UUID.randomUUID();
    organizationId = org;
    this.name = name;
    this.email = email;
    createdAt = now;
  }

  UUID id() {
    return id;
  }

  String name() {
    return name;
  }

  String email() {
    return email;
  }

  long version() {
    return version;
  }

  void update(String name, String email) {
    this.name = name;
    this.email = email;
    version++;
  }
}
