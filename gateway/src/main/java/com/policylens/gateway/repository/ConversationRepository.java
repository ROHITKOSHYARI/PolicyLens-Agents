package com.policylens.gateway.repository;
import com.policylens.gateway.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ConversationRepository extends JpaRepository<Conversation,UUID> {
 List<Conversation> findAllByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);
 Optional<Conversation> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);
}
