package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageProviderUnverifiedException;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 45 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that the system marks AWS S3 and Cloudflare R2 as verified targets only when
 * their respective compliance contract test suites have executed and passed;
 * unverified providers fail closed when strict verification is required.
 */
class ProviderCompatibilityCannotBeClaimedBeforeSuitePassesTest {

    @Test
    @DisplayName("45. Unverified provider fails closed when strict verification is required")
    void unverifiedProviderFailsClosedUnderStrictVerification() {
        S3StorageConfiguration unverifiedConfig = new S3StorageConfiguration(
                URI.create("https://s3.us-east-1.amazonaws.com"),
                "us-east-1",
                StorageBucket.of("prod-bucket"),
                S3Credentials.of("key", "secret"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.AWS_S3,
                null,
                5 * 1024 * 1024,
                5 * 1024 * 1024,
                3,
                true, // strict verification required!
                ProviderVerificationStatus.UNVERIFIED);

        assertThatThrownBy(() -> new S3ObjectStorageAdapter(unverifiedConfig, new MockS3HttpTransport()))
                .isInstanceOf(StorageProviderUnverifiedException.class)
                .hasMessageContaining("requires COMPLIANCE_VERIFIED status");
    }
}
