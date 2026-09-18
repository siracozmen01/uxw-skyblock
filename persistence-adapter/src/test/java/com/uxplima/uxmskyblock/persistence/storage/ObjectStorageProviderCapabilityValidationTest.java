package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability;
import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageCapabilityMissingException;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Architectural Contract Test 26 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that configuring a storage provider lacking required capability flags
 * causes application startup or operation preparation to fail fast and fail closed.
 */
class ObjectStorageProviderCapabilityValidationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("26. Local filesystem adapter rejects required unsupported capabilities")
    void localFilesystemAdapterRejectsUnsupportedCapabilities() {
        LocalFilesystemStorageAdapter adapter = new LocalFilesystemStorageAdapter(tempDir);

        // Supported: STREAMING_TRANSFER, RANGE_READ, SERVER_SIDE_COPY, CHECKSUM_ALGORITHMS
        assertThatCode(() -> adapter.validateRequiredCapabilities(
                        Set.of(ObjectStorageCapability.STREAMING_TRANSFER, ObjectStorageCapability.RANGE_READ)))
                .doesNotThrowAnyException();

        // Unsupported: MULTIPART_UPLOAD, OBJECT_LOCK
        assertThatThrownBy(() -> adapter.validateRequiredCapabilities(
                        Set.of(ObjectStorageCapability.MULTIPART_UPLOAD, ObjectStorageCapability.OBJECT_LOCK)))
                .isInstanceOf(StorageCapabilityMissingException.class)
                .hasMessageContaining("lacks required capabilities");
    }

    @Test
    @DisplayName("26. S3 generic target rejects unsupported enterprise capabilities")
    void genericS3RejectsUnsupportedCapabilities() {
        S3StorageConfiguration config = S3StorageConfiguration.createDefault(
                URI.create("https://minio.local:9000"),
                "us-east-1",
                StorageBucket.of("test-bucket"),
                S3Credentials.of("minioadmin", "minioadmin"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.GENERIC_S3);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, new MockS3HttpTransport());

        // Generic supports MULTIPART_UPLOAD, RANGE_READ, CHECKSUM_ALGORITHMS, STREAMING_TRANSFER
        assertThatCode(() -> adapter.validateRequiredCapabilities(
                        Set.of(ObjectStorageCapability.MULTIPART_UPLOAD, ObjectStorageCapability.STREAMING_TRANSFER)))
                .doesNotThrowAnyException();

        // Generic lacks OBJECT_LOCK and NATIVE_OBJECT_VERSIONING
        assertThatThrownBy(() -> adapter.validateRequiredCapabilities(Set.of(ObjectStorageCapability.OBJECT_LOCK)))
                .isInstanceOf(StorageCapabilityMissingException.class)
                .hasMessageContaining("lacks required capabilities");
    }

    @Test
    @DisplayName("26. AWS S3 provider target supports complete capability suite")
    void awsS3SupportsCompleteCapabilitySuite() {
        S3StorageConfiguration config = S3StorageConfiguration.createDefault(
                        URI.create("https://s3.us-east-1.amazonaws.com"),
                        "us-east-1",
                        StorageBucket.of("test-bucket"),
                        S3Credentials.of("access", "secret"),
                        S3AddressingMode.PATH_STYLE,
                        S3ProviderTarget.AWS_S3)
                .withVerificationStatus(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, new MockS3HttpTransport());

        assertThatCode(() -> adapter.validateRequiredCapabilities(Set.of(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.OBJECT_LOCK,
                        ObjectStorageCapability.PROVIDER_LIFECYCLE_RULES,
                        ObjectStorageCapability.NATIVE_OBJECT_VERSIONING)))
                .doesNotThrowAnyException();
    }
}
