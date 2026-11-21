package com.policylens.gateway.repository;
import com.policylens.gateway.domain.Message;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface MessageRepository extends JpaRepository<Message,UUID> {
 @Query("select m from Message m where m.tenantId=:tenant and m.userId=:user and m.conversation.id=:conversation order by m.createdAt desc")
 List<Message> recent(@Param("tenant") UUID tenant, @Param("user") UUID user, @Param("conversation") UUID conversation, org.springframework.data.domain.Pageable pageable);
 @Query("select m from Message m where m.tenantId=:tenant and m.userId=:user and m.conversation.id=:conversation order by m.createdAt asc")
 List<Message> allFor(@Param("tenant") UUID tenant, @Param("user") UUID user, @Param("conversation") UUID conversation);
}
