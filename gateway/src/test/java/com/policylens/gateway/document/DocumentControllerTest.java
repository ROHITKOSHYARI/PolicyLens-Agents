package com.policylens.gateway.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policylens.gateway.domain.DocumentStatus;
import com.policylens.gateway.repository.DocumentRepository;
import com.policylens.gateway.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies gateway upload validation and tenant isolation for every document route. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.upload.max-file-size-bytes=64",
        "app.upload.directory=${java.io.tmpdir}/policylens-document-test-uploads"
})
class DocumentControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DocumentRepository documents;
    @Autowired JwtService jwtService;
    @MockBean AgentServiceClient agentClient;

    @Test
    void uploadSanitizesFilenameAndReturnsReadyDocument() throws Exception {
        String token = registerAndLogin("upload");
        MockMultipartFile file = new MockMultipartFile("file", "..\\private\\policy.pdf", "text/plain", "%PDF-1.7 test".getBytes());
        String response = mvc.perform(multipart("/api/documents").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("policy.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        var json = mapper.readTree(response);
        verify(agentClient).ingest(any(), eq(UUID.fromString(json.get("id").asText())), eq("policy.pdf"), eq("application/pdf"), any());
    }

    @Test
    void anotherTenantCannotListLookupOrDeleteDocument() throws Exception {
        String ownerToken = registerAndLogin("owner");
        String strangerToken = registerAndLogin("stranger");
        MockMultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", "refund policy".getBytes());
        String result = mvc.perform(multipart("/api/documents").file(file).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String documentId = mapper.readTree(result).get("id").asText();

        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status", equalTo(404)));
        mvc.perform(delete("/api/documents/{id}", documentId).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        verify(agentClient, never()).delete(any(), any());
    }

    @Test
    void rejectsEmptyUnsupportedAndOversizedFilesWithJsonErrors() throws Exception {
        String token = registerAndLogin("invalid-upload");
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status", equalTo(400)));
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "bad.exe", "application/pdf", "%PDF-1.7".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a PDF".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "large.txt", "text/plain", new byte[65]))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.status", equalTo(413)));
    }

    @Test
    void ingestionFailureLeavesFailedRecord() throws Exception {
        String token = registerAndLogin("failed-upload");
        doThrow(new AgentServiceClient.AgentServiceException(502, "Agent service is unavailable"))
                .when(agentClient).ingest(any(), any(), anyString(), anyString(), any());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "policy.txt", "text/plain", "policy text".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.status", equalTo(502)));
        org.junit.jupiter.api.Assertions.assertEquals(1, documents.findAll().stream()
                .filter(document -> document.getStatus() == DocumentStatus.FAILED && document.getOriginalFilename().equals("policy.txt"))
                .count());
    }

    @Test
    void deleteRemovesDocumentAndCallsAgentWithTenantScope() throws Exception {
        String token = registerAndLogin("delete");
        MockMultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", "policy text".getBytes());
        String response = mvc.perform(multipart("/api/documents").file(file).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID documentId = UUID.fromString(mapper.readTree(response).get("id").asText());
        var principalClaims = jwtService.parse(token);
        mvc.perform(delete("/api/documents/{id}", documentId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertFalse(documents.existsById(documentId));
        verify(agentClient).delete(eq(UUID.fromString(principalClaims.get("tenantId", String.class))), eq(documentId));
    }

    private String registerAndLogin(String suffix) throws Exception {
        String email = suffix + "-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"secure-password\",\"tenantName\":\"Tenant %s\"}".formatted(email, UUID.randomUUID())))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"secure-password\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }
}
