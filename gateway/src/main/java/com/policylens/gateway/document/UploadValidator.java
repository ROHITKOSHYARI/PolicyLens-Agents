package com.policylens.gateway.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** Validates upload size, extension, and content independently of the client MIME header. */
public final class UploadValidator {
    private UploadValidator() { }

    public static ValidatedUpload validate(String clientFilename, byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        if (bytes.length > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the configured upload limit");
        String original = sanitizeDisplayFilename(clientFilename);
        int dot = original.lastIndexOf('.');
        if (dot < 1) throw unsupported();
        String extension = original.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mediaType = switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            default -> throw unsupported();
        };
        switch (extension) {
            case "pdf" -> {
                if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw unsupported();
            }
            case "docx" -> validateDocx(bytes);
            case "txt" -> validateUtf8(bytes, false);
            case "csv" -> validateUtf8(bytes, true);
            default -> throw unsupported();
        }
        return new ValidatedUpload(original, UUID.randomUUID() + "." + extension, mediaType, bytes);
    }

    private static String sanitizeDisplayFilename(String filename) {
        if (filename == null || filename.isBlank()) throw unsupported();
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (basename.isBlank() || basename.length() > 255) throw unsupported();
        return basename;
    }

    private static void validateDocx(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw unsupported();
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            int count = 0;
            while (entry != null) {
                if (++count > 5000) throw unsupported();
                entries.add(entry.getName());
                entry = zip.getNextEntry();
            }
        } catch (java.io.IOException exception) {
            throw unsupported();
        }
        if (!entries.contains("[Content_Types].xml") || !entries.contains("word/document.xml")) throw unsupported();
    }

    private static void validateUtf8(byte[] bytes, boolean csv) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0 || text.isBlank()) throw unsupported();
            if (csv) validateCsvQuotes(text);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw unsupported();
        }
    }

    private static void validateCsvQuotes(String text) {
        boolean inQuotes = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"') {
                if (inQuotes && index + 1 < text.length() && text.charAt(index + 1) == '"') index++;
                else inQuotes = !inQuotes;
            }
        }
        if (inQuotes) throw unsupported();
    }

    private static ResponseStatusException unsupported() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Supported uploads are valid PDF, DOCX, TXT, and CSV files");
    }

    public record ValidatedUpload(String originalFilename, String storedFilename, String contentType, byte[] bytes) { }
}
