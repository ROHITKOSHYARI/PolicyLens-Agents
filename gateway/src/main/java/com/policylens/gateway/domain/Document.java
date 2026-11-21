package com.policylens.gateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Tenant-owned document metadata tracked by the public gateway. */
@Entity
@Table(name = "documents", indexes = @Index(name = "idx_documents_tenant", columnList = "tenant_id"))
public class Document {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    @Column(name = "stored_filename", nullable = false, unique = true, length = 80)
    private String storedFilename;
    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;
    @Column(name = "size", nullable = false)
    private long size;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Document() { }

    public Document(UUID tenantId, UUID userId, String originalFilename, String storedFilename,
                    String contentType, long size) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.userId = userId;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.contentType = contentType;
        this.size = size;
        this.status = DocumentStatus.PROCESSING;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStoredFilename() { return storedFilename; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public DocumentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(DocumentStatus status) { this.status = status; }
}
