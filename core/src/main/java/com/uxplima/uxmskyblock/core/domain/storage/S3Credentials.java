package com.uxplima.uxmskyblock.core.domain.storage;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Authentication credentials for S3-compatible endpoints.
 *
 * <p>Strict Invariant (Contract Test 28): Access keys, secret keys, and session tokens
 * MUST be redacted from diagnostic logs, exception traces, and string serialization.
 *
 * @param accessKey public access key ID
 * @param secretKey private secret access key
 * @param sessionToken optional STS session token
 */
public record S3Credentials(
        String accessKey, String secretKey, @Nullable String sessionToken) {

    public S3Credentials {
        Objects.requireNonNull(accessKey, "accessKey must not be null");
        Objects.requireNonNull(secretKey, "secretKey must not be null");
        if (accessKey.isBlank()) {
            throw new IllegalArgumentException("accessKey must not be blank");
        }
        if (secretKey.isBlank()) {
            throw new IllegalArgumentException("secretKey must not be blank");
        }
    }

    public static S3Credentials of(String accessKey, String secretKey) {
        return new S3Credentials(accessKey, secretKey, null);
    }

    public static S3Credentials of(String accessKey, String secretKey, @Nullable String sessionToken) {
        return new S3Credentials(accessKey, secretKey, sessionToken);
    }

    @Override
    public String toString() {
        return "S3Credentials[accessKey=REDACTED, secretKey=REDACTED, sessionToken="
                + (sessionToken != null ? "REDACTED" : "null") + "]";
    }
}
