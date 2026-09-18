package com.uxplima.uxmskyblock.core.application.storage;

import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;

/**
 * Outbound application port for general object storage operations across local and remote providers.
 */
public interface ObjectStoragePort {

    /**
     * Persists binary object data with metadata.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     * @param data binary payload
     * @param metadata object metadata
     */
    void putObject(StorageBucket bucket, String objectKey, byte[] data, StorageObjectMetadata metadata);

    /**
     * Retrieves binary object payload if it exists.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     * @return optional containing binary data if found
     */
    Optional<byte[]> getObject(StorageBucket bucket, String objectKey);

    /**
     * Checks whether an object exists in storage.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     * @return true if object exists
     */
    boolean exists(StorageBucket bucket, String objectKey);

    /**
     * Deletes an object from storage if present.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     */
    void deleteObject(StorageBucket bucket, String objectKey);

    /**
     * Lists object keys under the specified prefix.
     *
     * @param bucket target bucket
     * @param prefix key prefix to match
     * @return list of matching object keys
     */
    List<String> listObjects(StorageBucket bucket, String prefix);

    /**
     * Retrieves metadata for an object if present.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     * @return optional containing metadata if found
     */
    Optional<StorageObjectMetadata> getMetadata(StorageBucket bucket, String objectKey);

    /**
     * Bounded memory streaming upload of an object.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     * @param inputStream data input stream
     * @param metadata object metadata
     */
    default void putStream(
            StorageBucket bucket, String objectKey, java.io.InputStream inputStream, StorageObjectMetadata metadata) {
        try {
            putObject(bucket, objectKey, inputStream.readAllBytes(), metadata);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read input stream for object: " + objectKey, e);
        }
    }

    /**
     * Bounded memory streaming download of an object.
     *
     * @param bucket target bucket
     * @param objectKey unique object key / path
     * @return optional input stream if object exists
     */
    default Optional<java.io.InputStream> openStream(StorageBucket bucket, String objectKey) {
        return getObject(bucket, objectKey).map(java.io.ByteArrayInputStream::new);
    }

    /**
     * Returns the typed capabilities supported by this storage provider.
     */
    default java.util.Set<com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability> capabilities() {
        return java.util.Set.of();
    }

    /**
     * Identifier of the underlying storage provider.
     */
    default com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId providerId() {
        return com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId.of("generic");
    }

    /**
     * Validates that this provider supports all required capabilities, failing fast and fail closed if any are missing.
     *
     * @param requiredCapabilities set of required capabilities
     */
    default void validateRequiredCapabilities(
            java.util.Set<com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability> requiredCapabilities) {
        java.util.Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null");
        java.util.Set<com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability> supported = capabilities();
        java.util.Set<com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability> missing =
                new java.util.HashSet<>(requiredCapabilities);
        missing.removeAll(supported);
        if (!missing.isEmpty()) {
            throw new com.uxplima.uxmskyblock.core.domain.storage.StorageCapabilityMissingException(
                    "Storage provider " + providerId() + " lacks required capabilities: " + missing);
        }
    }
}
