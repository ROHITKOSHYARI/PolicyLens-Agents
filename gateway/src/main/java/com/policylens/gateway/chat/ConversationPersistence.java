package com.policylens.gateway.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.policylens.gateway.domain.Conversation;
import com.policylens.gateway.domain.Message;
import com.policylens.gateway.repository.ConversationRepository;
import com.policylens.gateway.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits each completed user/assistant exchange atomically. */
@Service
public class ConversationPersistence {
 private final ConversationRepository conversations;
 private final MessageRepository messages;
 public ConversationPersistence(ConversationRepository conversations, MessageRepository messages){this.conversations=conversations;this.messages=messages;}
 @Transactional
 public void saveExchange(Conversation conversation,String question,JsonNode response){
  conversations.save(conversation);
  String citations=response.path("citations").isMissingNode()?"[]":response.path("citations").toString();
  messages.save(new Message(conversation,"user",question,"[]",null,false,0,0,0));
  messages.save(new Message(conversation,"assistant",response.path("answer").asText(""),citations,
   response.path("route").asText("BASELINE_RAG"),response.path("cached").asBoolean(),response.path("tokensIn").asInt(),response.path("tokensOut").asInt(),response.path("latencyMs").asLong()));
 }
}
