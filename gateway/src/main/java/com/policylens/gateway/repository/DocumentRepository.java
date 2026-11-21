package com.policylens.gateway.repository;

import com.policylens.gateway.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped document metadata access. */
public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    Optional<Document> findByIdAndTenantId(UUID id, UUID tenantId);
}
