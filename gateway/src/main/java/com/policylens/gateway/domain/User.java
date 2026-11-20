package com.policylens.gateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A registered user belonging to exactly one tenant. */
@Entity
@Table(name = "users")
public class User {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 320)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() { }
    public User(String email, String passwordHash, Tenant tenant) {
        this.id = UUID.randomUUID(); this.email = email; this.passwordHash = passwordHash;
        this.tenant = tenant; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Tenant getTenant() { return tenant; }
    public Instant getCreatedAt() { return createdAt; }
}
