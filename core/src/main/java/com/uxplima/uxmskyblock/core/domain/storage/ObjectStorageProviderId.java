package com.uxplima.uxmskyblock.core.domain.storage;

import java.util.Locale;
import java.util.Objects;

/**
 * Strongly typed identifier for an object storage provider.
 *
 * @param value provider identifier string (e.g. "aws-s3", "cloudflare-r2", "generic-s3", "local-fs")
 */
public record ObjectStorageProviderId(String value) {

    public static final ObjectStorageProviderId AWS_S3 = ObjectStorageProviderId.of("aws-s3");
    public static final ObjectStorageProviderId CLOUDFLARE_R2 = ObjectStorageProviderId.of("cloudflare-r2");
    public static final ObjectStorageProviderId GENERIC_S3 = ObjectStorageProviderId.of("generic-s3");
    public static final ObjectStorageProviderId LOCAL_FS = ObjectStorageProviderId.of("local-fs");

    public ObjectStorageProviderId {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Provider identifier cannot be empty");
        }
    }

    public static ObjectStorageProviderId of(String value) {
        return new ObjectStorageProviderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
