package com.policylens.gateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="messages")
public class Message {
 @Id private UUID id;
 @Column(name="tenant_id", nullable=false) private UUID tenantId;
 @Column(name="user_id", nullable=false) private UUID userId;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="conversation_id", nullable=false) private Conversation conversation;
 @Column(nullable=false, length=20) private String role;
 @Column(nullable=false, columnDefinition="TEXT") private String content;
 @Column(nullable=false, columnDefinition="JSON") private String citations;
 @Column(length=40) private String route;
 @Column(nullable=false) private boolean cached;
 @Column(name="tokens_in", nullable=false) private int tokensIn;
 @Column(name="tokens_out", nullable=false) private int tokensOut;
 @Column(name="latency_ms", nullable=false) private long latencyMs;
 @Column(name="created_at", nullable=false) private Instant createdAt;
 protected Message() {}
 public Message(Conversation c, String role, String content, String citations, String route, boolean cached, int in, int out, long latency) {
  this.id=UUID.randomUUID(); this.conversation=c; this.tenantId=c.getTenantId(); this.userId=c.getUserId(); this.role=role; this.content=content; this.citations=citations; this.route=route; this.cached=cached; this.tokensIn=in; this.tokensOut=out; this.latencyMs=latency; this.createdAt=Instant.now();
 }
 public UUID getId(){return id;} public Conversation getConversation(){return conversation;} public String getRole(){return role;} public String getContent(){return content;} public String getCitations(){return citations;} public String getRoute(){return route;} public boolean isCached(){return cached;} public int getTokensIn(){return tokensIn;} public int getTokensOut(){return tokensOut;} public long getLatencyMs(){return latencyMs;} public Instant getCreatedAt(){return createdAt;}
}
