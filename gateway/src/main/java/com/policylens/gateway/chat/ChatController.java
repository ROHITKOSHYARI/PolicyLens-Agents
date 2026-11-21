package com.policylens.gateway.chat;
import com.policylens.gateway.security.GatewayPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ChatController {
 private final ConversationService service;
 public ChatController(ConversationService service){this.service=service;}
 @PostMapping("/api/chat") @ResponseStatus(HttpStatus.OK)
 public ConversationService.ChatResult chat(@AuthenticationPrincipal GatewayPrincipal principal,@Valid @RequestBody ChatRequest request){
  return service.chat(principal.tenantId(),principal.userId(),request.conversationId(),request.question());
 }
 @GetMapping("/api/conversations")
 public List<ConversationService.ConversationView> list(@AuthenticationPrincipal GatewayPrincipal p){return service.list(p.tenantId(),p.userId());}
 @GetMapping("/api/conversations/{id}")
 public ConversationService.ConversationView get(@AuthenticationPrincipal GatewayPrincipal p,@PathVariable UUID id){return service.get(p.tenantId(),p.userId(),id);}
 public record ChatRequest(@Size(max=4000) @NotBlank String question, UUID conversationId){}
}
