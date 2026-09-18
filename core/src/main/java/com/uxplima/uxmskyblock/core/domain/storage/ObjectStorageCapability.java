package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * Typed capability flags discoverable across object storage providers.
 */
public enum ObjectStorageCapability {
    /**
     * Supports multipart upload for large objects with bounded chunk sizes.
     */
    MULTIPART_UPLOAD,

    /**
     * Supports byte-range read queries.
     */
    RANGE_READ,

    /**
     * Supports server-side object copying without client-side re-upload.
     */
    SERVER_SIDE_COPY,

    /**
     * Supports conditional writes (If-Match, If-None-Match).
     */
    CONDITIONAL_WRITE,

    /**
     * Supports generating presigned download or upload URLs.
     */
    PRESIGNED_URL,

    /**
     * Supports native object versioning.
     */
    NATIVE_OBJECT_VERSIONING,

    /**
     * Supports WORM (Write Once Read Many) object locking.
     */
    OBJECT_LOCK,

    /**
     * Supports provider-level lifecycle transition rules.
     */
    PROVIDER_LIFECYCLE_RULES,

    /**
     * Supports application or provider checksum algorithm negotiation (SHA-256, CRC32C).
     */
    CHECKSUM_ALGORITHMS,

    /**
     * Supports bounded memory streaming transfers without full in-heap buffering.
     */
    STREAMING_TRANSFER
}
