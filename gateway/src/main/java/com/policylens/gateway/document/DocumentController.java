package com.policylens.gateway.document;

import com.policylens.gateway.domain.Document;
import com.policylens.gateway.security.GatewayPrincipal;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped document upload, listing, lookup, and deletion endpoints. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documents;

    public DocumentController(DocumentService documents) { this.documents = documents; }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@AuthenticationPrincipal GatewayPrincipal principal,
                                   @RequestPart("file") @NotNull MultipartFile file) {
        return DocumentResponse.from(documents.upload(principal.tenantId(), principal.userId(), file));
    }

    @GetMapping
    public List<DocumentResponse> list(@AuthenticationPrincipal GatewayPrincipal principal) {
        return documents.list(principal.tenantId()).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{documentId}")
    public DocumentResponse get(@AuthenticationPrincipal GatewayPrincipal principal, @PathVariable UUID documentId) {
        return DocumentResponse.from(documents.get(principal.tenantId(), documentId));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal GatewayPrincipal principal, @PathVariable UUID documentId) {
        documents.delete(principal.tenantId(), documentId);
    }

    public record DocumentResponse(UUID id, String originalFilename, String contentType, long size,
                                   String status, Instant createdAt) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(document.getId(), document.getOriginalFilename(), document.getContentType(),
                    document.getSize(), document.getStatus().name(), document.getCreatedAt());
        }
    }
}
