package com.policylens.gateway.document;

import com.policylens.gateway.domain.Document;
import com.policylens.gateway.domain.DocumentStatus;
import com.policylens.gateway.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Owns tenant-scoped document lifecycle and agent-service forwarding. */
@Service
public class DocumentService {
    private final DocumentRepository documents;
    private final AgentServiceClient agentClient;
    private final Path uploadDirectory;
    private final long maxFileSize;

    public DocumentService(DocumentRepository documents, AgentServiceClient agentClient,
                           @Value("${app.upload.directory}") String uploadDirectory,
                           @Value("${app.upload.max-file-size-bytes}") long maxFileSize) {
        this.documents = documents;
        this.agentClient = agentClient;
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
    }

    public Document upload(UUID tenantId, UUID userId, MultipartFile file) {
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file"); }
        UploadValidator.ValidatedUpload upload = UploadValidator.validate(file.getOriginalFilename(), bytes, maxFileSize);
        Document document = documents.saveAndFlush(new Document(tenantId, userId, upload.originalFilename(),
                upload.storedFilename(), upload.contentType(), bytes.length));
        Path storedPath = uploadDirectory.resolve(upload.storedFilename()).normalize();
        if (!storedPath.getParent().equals(uploadDirectory)) {
            document.setStatus(DocumentStatus.FAILED);
            documents.saveAndFlush(document);
            throw new IllegalStateException("Generated storage filename escaped upload directory");
        }
        try {
            Files.createDirectories(uploadDirectory);
            Files.write(storedPath, bytes);
            agentClient.ingest(tenantId, document.getId(), upload.originalFilename(), upload.contentType(), bytes);
            document.setStatus(DocumentStatus.READY);
            return documents.save(document);
        } catch (AgentServiceClient.AgentServiceException exception) {
            document.setStatus(DocumentStatus.FAILED);
            documents.saveAndFlush(document);
            throw exception;
        } catch (IOException exception) {
            document.setStatus(DocumentStatus.FAILED);
            documents.saveAndFlush(document);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store uploaded file");
        }
    }

    public List<Document> list(UUID tenantId) {
        return documents.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public Document get(UUID tenantId, UUID documentId) {
        return documents.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    public void delete(UUID tenantId, UUID documentId) {
        Document document = get(tenantId, documentId);
        agentClient.delete(tenantId, documentId);
        documents.delete(document);
        try { Files.deleteIfExists(uploadDirectory.resolve(document.getStoredFilename()).normalize()); }
        catch (IOException ignored) { /* Metadata and retrieval data are already deleted. */ }
    }
}
