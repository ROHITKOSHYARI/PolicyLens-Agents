package com.policylens.gateway.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Authenticated client for agent-service document ingestion and deletion. */
@Component
public class AgentServiceClient {
    private final RestClient client;
    private final String serviceToken;

    public AgentServiceClient(RestClient.Builder builder,
                              @Value("${app.agent.url}") String baseUrl,
                              @Value("${app.agent.service-token}") String serviceToken,
                              @Value("${app.agent.connect-timeout}") Duration connectTimeout,
                              @Value("${app.agent.read-timeout}") Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        this.client = builder.requestFactory(requestFactory).baseUrl(baseUrl).build();
        this.serviceToken = serviceToken;
    }

    public void ingest(UUID tenantId, UUID documentId, String filename, String contentType, byte[] bytes) {
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("document_id", documentId.toString());
        form.add("filename", filename);
        form.add("file", new HttpEntity<>(file, fileHeaders));
        try {
            client.post().uri("/internal/ingest")
                    .header("X-Service-Token", serviceToken)
                    .header("X-Tenant-Id", tenantId.toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new AgentServiceException(422, "Agent service rejected the document");
            }
            throw new AgentServiceException(502, "Agent service is unavailable");
        } catch (ResourceAccessException exception) {
            throw new AgentServiceException(502, "Agent service is unavailable");
        }
    }

    public void delete(UUID tenantId, UUID documentId) {
        try {
            client.delete().uri("/internal/documents/{documentId}", documentId)
                    .header("X-Service-Token", serviceToken)
                    .header("X-Tenant-Id", tenantId.toString())
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException | ResourceAccessException exception) {
            throw new AgentServiceException(502, "Agent service is unavailable");
        }
    }

    /** Represents a failed downstream document operation. */
    public static class AgentServiceException extends RuntimeException {
        private final int status;
        public AgentServiceException(int status, String message) { super(message); this.status = status; }
        public int getStatus() { return status; }
    }
}
