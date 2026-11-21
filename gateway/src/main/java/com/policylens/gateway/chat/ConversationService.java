package com.policylens.gateway.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.policylens.gateway.document.AgentServiceClient;
import com.policylens.gateway.domain.Conversation;
import com.policylens.gateway.domain.Message;
import com.policylens.gateway.repository.ConversationRepository;
import com.policylens.gateway.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class ConversationService {
 private final ConversationRepository conversations; private final MessageRepository messages;
 private final AgentServiceClient agent; private final ConversationPersistence persistence; private final int historyLimit;
 public ConversationService(ConversationRepository conversations, MessageRepository messages, AgentServiceClient agent,
   ConversationPersistence persistence, @Value("${app.chat.history-limit:10}") int historyLimit) {
  this.conversations=conversations; this.messages=messages; this.agent=agent; this.persistence=persistence; this.historyLimit=historyLimit;
 }
 public ChatResult chat(UUID tenant, UUID user, UUID conversationId, String question) {
  Conversation conversation = conversationId == null ? null : conversations.findByIdAndTenantIdAndUserId(conversationId, tenant, user)
     .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
  List<Map<String,String>> history = conversation == null ? List.of() : messages.recent(tenant,user,conversation.getId(),PageRequest.of(0,historyLimit)).stream()
      .sorted(Comparator.comparing(Message::getCreatedAt)).map(m -> Map.of("role",m.getRole(),"content",m.getContent())).toList();
  JsonNode result=agent.chat(tenant,question,history);
  if (result == null) throw new AgentServiceClient.AgentServiceException(502,"Agent service returned an invalid response");
  if(conversation==null) conversation=new Conversation(tenant,user);
  persistence.saveExchange(conversation, question, result);
  return new ChatResult(conversation.getId(), result.path("answer").asText(""), result.path("citations"),
      result.path("tokensIn").asInt(), result.path("tokensOut").asInt(), result.path("latencyMs").asLong(),
      result.path("cached").asBoolean(), result.path("route").asText("BASELINE_RAG"));
 }
 @org.springframework.transaction.annotation.Transactional(readOnly=true)
 public List<ConversationView> list(UUID tenant,UUID user){return conversations.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(tenant,user).stream().map(c->view(c,tenant,user)).toList();}
 @org.springframework.transaction.annotation.Transactional(readOnly=true)
 public ConversationView get(UUID tenant,UUID user,UUID id){Conversation c=conversations.findByIdAndTenantIdAndUserId(id,tenant,user).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation not found"));return view(c,tenant,user);}
 private ConversationView view(Conversation c,UUID tenant,UUID user){return new ConversationView(c.getId(),c.getCreatedAt(),messages.allFor(tenant,user,c.getId()).stream().map(m->new MessageView(m.getRole(),m.getContent(),m.getCitations(),m.getRoute(),m.isCached(),m.getTokensIn(),m.getTokensOut(),m.getLatencyMs(),m.getCreatedAt())).toList());}
 public record ChatResult(UUID conversationId,String answer,JsonNode citations,int tokensIn,int tokensOut,long latencyMs,boolean cached,String route){}
 public record ConversationView(UUID id,java.time.Instant createdAt,List<MessageView> messages){}
 public record MessageView(String role,String content,String citations,String route,boolean cached,int tokensIn,int tokensOut,long latencyMs,java.time.Instant createdAt){}
}
