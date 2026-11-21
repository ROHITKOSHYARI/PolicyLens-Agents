package com.policylens.gateway.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.policylens.gateway.document.AgentServiceClient;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/** Maps expected API failures to a stable error shape. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidRequest() { return response(HttpStatus.BAD_REQUEST, "Request validation failed"); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException exception) { return response(HttpStatus.BAD_REQUEST, exception.getMessage()); }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> unauthenticated() { return response(HttpStatus.UNAUTHORIZED, "Invalid email or password"); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> conflict() { return response(HttpStatus.CONFLICT, "A record with these details already exists"); }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> responseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return response(status, exception.getReason() == null ? status.getReasonPhrase() : exception.getReason());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> uploadTooLarge() { return response(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the configured upload limit"); }

    @ExceptionHandler(AgentServiceClient.AgentServiceException.class)
    ResponseEntity<ErrorResponse> agentFailure(AgentServiceClient.AgentServiceException exception) {
        return response(HttpStatus.valueOf(exception.getStatus()), exception.getMessage());
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), message, Instant.now()));
    }
}
