package com.uxplima.uxmskyblock.core.domain.storage;

import java.net.URI;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration descriptor for an S3-compatible remote storage endpoint.
 *
 * <p>Strict Invariant: Credential secrets are never printed in diagnostic string representations.
 */
public record S3StorageConfiguration(
        URI endpoint,
        String region,
        StorageBucket bucket,
        S3Credentials credentials,
        S3AddressingMode addressingMode,
        S3ProviderTarget providerTarget,
        @Nullable String pathPrefix,
        long multipartThresholdBytes,
        long partSizeBytes,
        int maxRetries,
        boolean strictVerificationRequired,
        ProviderVerificationStatus verificationStatus) {

    public static final long DEFAULT_MULTIPART_THRESHOLD = 5L * 1024L * 1024L; // 5 MB (S3 min part size)
    public static final long DEFAULT_PART_SIZE = 5L * 1024L * 1024L; // 5 MB
    public static final int DEFAULT_MAX_RETRIES = 3;

    public S3StorageConfiguration {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(credentials, "credentials must not be null");
        Objects.requireNonNull(addressingMode, "addressingMode must not be null");
        Objects.requireNonNull(providerTarget, "providerTarget must not be null");
        Objects.requireNonNull(verificationStatus, "verificationStatus must not be null");

        if (multipartThresholdBytes < 1024 * 1024) {
            throw new IllegalArgumentException(
                    "multipartThresholdBytes must be at least 1MB: " + multipartThresholdBytes);
        }
        if (partSizeBytes < 1024 * 1024) {
            throw new IllegalArgumentException("partSizeBytes must be at least 1MB: " + partSizeBytes);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
    }

    public static S3StorageConfiguration createDefault(
            URI endpoint,
            String region,
            StorageBucket bucket,
            S3Credentials credentials,
            S3AddressingMode addressingMode,
            S3ProviderTarget providerTarget) {
        return new S3StorageConfiguration(
                endpoint,
                region,
                bucket,
                credentials,
                addressingMode,
                providerTarget,
                null,
                DEFAULT_MULTIPART_THRESHOLD,
                DEFAULT_PART_SIZE,
                DEFAULT_MAX_RETRIES,
                false,
                ProviderVerificationStatus.UNVERIFIED);
    }

    public S3StorageConfiguration withVerificationStatus(ProviderVerificationStatus newStatus) {
        return new S3StorageConfiguration(
                endpoint,
                region,
                bucket,
                credentials,
                addressingMode,
                providerTarget,
                pathPrefix,
                multipartThresholdBytes,
                partSizeBytes,
                maxRetries,
                strictVerificationRequired,
                newStatus);
    }

    @Override
    public String toString() {
        return "S3StorageConfiguration[endpoint=" + endpoint
                + ", region=" + region
                + ", bucket=" + bucket.name()
                + ", credentials=" + credentials
                + ", addressingMode=" + addressingMode
                + ", providerTarget=" + providerTarget
                + ", pathPrefix=" + pathPrefix
                + ", multipartThresholdBytes=" + multipartThresholdBytes
                + ", partSizeBytes=" + partSizeBytes
                + ", maxRetries=" + maxRetries
                + ", strictVerificationRequired=" + strictVerificationRequired
                + ", verificationStatus=" + verificationStatus + "]";
    }
}
