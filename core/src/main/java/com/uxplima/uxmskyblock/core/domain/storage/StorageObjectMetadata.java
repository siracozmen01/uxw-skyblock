package com.uxplima.uxmskyblock.core.domain.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata descriptor for an immutable or stored object payload.
 *
 * @param contentType MIME content type
 * @param contentLength size in bytes
 * @param sha256Checksum hexadecimal SHA-256 hash string
 * @param lastModified timestamp of durable persistence
 * @param customMetadata user or application defined key-value metadata
 */
public record StorageObjectMetadata(
        String contentType,
        long contentLength,
        String sha256Checksum,
        Instant lastModified,
        Map<String, String> customMetadata) {

    public StorageObjectMetadata {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256Checksum, "sha256Checksum");
        Objects.requireNonNull(lastModified, "lastModified");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength cannot be negative: " + contentLength);
        }
        customMetadata = (customMetadata == null) ? Map.of() : Map.copyOf(customMetadata);
    }

    public static StorageObjectMetadata of(
            String contentType, long contentLength, String sha256Checksum, Instant lastModified) {
        return new StorageObjectMetadata(contentType, contentLength, sha256Checksum, lastModified, Map.of());
    }

    public static StorageObjectMetadata empty() {
        return new StorageObjectMetadata("application/octet-stream", 0, "", Instant.EPOCH, Map.of());
    }
}
