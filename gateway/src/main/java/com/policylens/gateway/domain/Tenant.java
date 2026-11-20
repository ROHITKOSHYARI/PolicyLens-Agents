package com.policylens.gateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A tenant boundary for PolicyLens data. */
@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 160)
    private String name;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tenant() { }
    public Tenant(String name) { this.id = UUID.randomUUID(); this.name = name; this.createdAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
