package com.policylens.gateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="conversations")
public class Conversation {
 @Id private UUID id;
 @Column(name="tenant_id", nullable=false) private UUID tenantId;
 @Column(name="user_id", nullable=false) private UUID userId;
 @Column(name="created_at", nullable=false) private Instant createdAt;
 protected Conversation() {}
 public Conversation(UUID tenantId, UUID userId) { this.id=UUID.randomUUID(); this.tenantId=tenantId; this.userId=userId; this.createdAt=Instant.now(); }
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getUserId(){return userId;} public Instant getCreatedAt(){return createdAt;}
}
