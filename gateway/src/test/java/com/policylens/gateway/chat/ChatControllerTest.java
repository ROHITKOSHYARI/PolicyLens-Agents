package com.policylens.gateway.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.policylens.gateway.document.AgentServiceClient;
import com.policylens.gateway.domain.Conversation;
import com.policylens.gateway.domain.Message;
import com.policylens.gateway.repository.ConversationRepository;
import com.policylens.gateway.repository.MessageRepository;
import com.policylens.gateway.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@TestPropertySource(properties="app.chat.history-limit=2")
class ChatControllerTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JwtService jwt;
 @Autowired ConversationRepository conversations; @Autowired MessageRepository messages;
 @SpyBean MessageRepository messageRepositorySpy;
 @MockBean AgentServiceClient agent;

 @BeforeEach void cleanDatabase(){ messages.deleteAll(); conversations.deleteAll(); }

 @Test void createsThenAppendsAndCapsSameConversationHistory() throws Exception {
  String token=register("chat"); UUID tenant=tenant(token);
  when(agent.chat(eq(tenant),anyString(),anyList())).thenAnswer(inv->{
   var history=(List<Map<String,String>>)inv.getArgument(2);
   return mapper.valueToTree(Map.of("answer","answer","citations",List.of(),"tokensIn",1,"tokensOut",2,"latencyMs",3,"cached",false,"route","BASELINE_RAG"));
  });
  String first=mvc.perform(post("/api/chat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q1\"}"))
    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  UUID cid=UUID.fromString(mapper.readTree(first).get("conversationId").asText());
  mvc.perform(post("/api/chat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q2\",\"conversationId\":\""+cid+"\"}"))
    .andExpect(status().isOk());
  assertEquals(1,conversations.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(tenant,user(token)).size());
  assertEquals(4,messages.allFor(tenant,user(token),cid).size());
  verify(agent).chat(eq(tenant),eq("q2"),argThat(h->h.size()==2 && h.get(0).get("content").equals("q1") && h.get(1).get("content").equals("answer")));
 }

 @Test void historyDoesNotIncludeOtherConversationAndIsLimited() throws Exception {
  String token=register("history"); UUID tenant=tenant(token), uid=user(token);
  Conversation own=new Conversation(tenant,uid); Conversation other=new Conversation(tenant,uid); conversations.saveAll(List.of(own,other));
  messages.save(new Message(own,"user","own-old","[]",null,false,0,0,0));
  messages.save(new Message(own,"assistant","own-new","[]",null,false,0,0,0));
  messages.save(new Message(other,"user","SECRET-other-conversation","[]",null,false,0,0,0));
  when(agent.chat(eq(tenant),anyString(),anyList())).thenReturn(mapper.readTree("{\"answer\":\"ok\",\"citations\":[]}"));
  mvc.perform(post("/api/chat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
   .content("{\"question\":\"q\",\"conversationId\":\""+own.getId()+"\"}" )).andExpect(status().isOk());
  verify(agent).chat(eq(tenant),eq("q"),argThat(h->h.size()==2 && h.stream().noneMatch(m->m.get("content").contains("SECRET"))));
 }

 @Test void crossTenantAndCrossUserAccessReturn404AndListIsUserScoped() throws Exception {
  String owner=register("owner"), stranger=register("stranger"); UUID id=UUID.fromString(createChat(owner));
  mvc.perform(get("/api/conversations/{id}",id).header("Authorization","Bearer "+stranger)).andExpect(status().isNotFound());
  mvc.perform(post("/api/chat").header("Authorization","Bearer "+stranger).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\",\"conversationId\":\""+id+"\"}" )).andExpect(status().isNotFound());
  mvc.perform(get("/api/conversations").header("Authorization","Bearer "+stranger)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.hasSize(0)));
 }

 @Test void failureStoresNoConversationOrMessagesAndReturnsJsonError() throws Exception {
  String token=register("fail"); UUID t=tenant(token),u=user(token);
  when(agent.chat(eq(t),anyString(),anyList())).thenThrow(new AgentServiceClient.AgentServiceException(502,"Agent service is unavailable"));
  mvc.perform(post("/api/chat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}"))
    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value("Agent service is unavailable"));
  assertTrue(conversations.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(t,u).isEmpty()); assertTrue(messages.findAll().isEmpty());
 }

 @Test void assistantSaveFailureRollsBackUserMessage() throws Exception {
  String token=register("atomic"); UUID t=tenant(token),u=user(token);
  when(agent.chat(eq(t),anyString(),anyList())).thenReturn(mapper.readTree("{\"answer\":\"ok\",\"citations\":[]}"));
  org.mockito.Mockito.doThrow(new RuntimeException("injected assistant save failure"))
    .when(messageRepositorySpy).save(org.mockito.ArgumentMatchers.argThat(message -> "assistant".equals(message.getRole())));
  mvc.perform(post("/api/chat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}" )).andExpect(status().is5xxServerError());
  assertTrue(conversations.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(t,u).isEmpty());
  assertTrue(messages.findAll().isEmpty());
 }

 @Test void chatRequiresJwt() throws Exception {mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}")).andExpect(status().isUnauthorized());}

 private String createChat(String token)throws Exception { when(agent.chat(any(),anyString(),anyList())).thenReturn(mapper.readTree("{\"answer\":\"ok\",\"citations\":[]}")); String b=mvc.perform(post("/api/chat").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}")).andReturn().getResponse().getContentAsString();return mapper.readTree(b).get("conversationId").asText(); }
 private String register(String n)throws Exception {String email=n+"-"+UUID.randomUUID()+"@example.test";mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\"secure-password\",\"tenantName\":\""+n+UUID.randomUUID()+"\"}"));return mapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\"secure-password\"}")).andReturn().getResponse().getContentAsString()).get("token").asText();}
 private UUID tenant(String token){return UUID.fromString(jwt.parse(token).get("tenantId",String.class));} private UUID user(String token){return UUID.fromString(jwt.parse(token).get("userId",String.class));}
}
