package com.ledgerflow.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "ledgerflow")
class User {
  @Id private UUID id;

  @Column(nullable = false, length = 254)
  private String email;

  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected User() {}

  User(String email, String displayName, String passwordHash, Instant now) {
    this.id = UUID.randomUUID();
    this.email = email;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.createdAt = now;
  }

  UUID id() {
    return id;
  }

  String email() {
    return email;
  }

  String displayName() {
    return displayName;
  }

  String passwordHash() {
    return passwordHash;
  }
}
