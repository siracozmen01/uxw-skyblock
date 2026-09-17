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
}
