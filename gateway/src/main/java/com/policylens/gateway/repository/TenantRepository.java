package com.policylens.gateway.repository;

import com.policylens.gateway.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/** Database access for tenant records. */
public interface TenantRepository extends JpaRepository<Tenant, UUID> { }
