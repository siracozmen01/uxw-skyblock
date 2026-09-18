package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability;
import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId;
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

/**
 * Architectural Contract Test 31 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that generic custom S3-compatible endpoints negotiate capability subsets
 * safely and reject unsupported advanced operations before execution.
 */
class GenericS3EndpointCapabilityNegotiationTest {

    @Test
    @DisplayName("31. Generic S3 endpoint negotiates capability subsets safely")
    void genericS3NegotiatesCapabilitiesSafely() {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        StorageBucket bucket = StorageBucket.of("generic-bucket");

        S3StorageConfiguration genericConfig = new S3StorageConfiguration(
                URI.create("http://127.0.0.1:9000"),
                "us-east-1",
                bucket,
                S3Credentials.of("minioadmin", "minioadmin"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.GENERIC_S3,
                null,
                5 * 1024 * 1024,
                5 * 1024 * 1024,
                3,
                false,
                ProviderVerificationStatus.EMULATOR_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(genericConfig, transport);

        // Provider ID is GENERIC_S3
        assertThat(adapter.providerId()).isEqualTo(ObjectStorageProviderId.GENERIC_S3);

        // Supported generic capabilities
        assertThat(adapter.capabilities())
                .contains(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.STREAMING_TRANSFER,
                        ObjectStorageCapability.RANGE_READ,
                        ObjectStorageCapability.CHECKSUM_ALGORITHMS);

        // Unsupported advanced enterprise capabilities are absent
        assertThat(adapter.capabilities())
                .doesNotContain(
                        ObjectStorageCapability.OBJECT_LOCK,
                        ObjectStorageCapability.PROVIDER_LIFECYCLE_RULES,
                        ObjectStorageCapability.NATIVE_OBJECT_VERSIONING);

        // Fail-closed validation for missing advanced capabilities
        assertThatThrownBy(() -> adapter.validateRequiredCapabilities(Set.of(ObjectStorageCapability.OBJECT_LOCK)))
                .isInstanceOf(StorageCapabilityMissingException.class)
                .hasMessageContaining("lacks required capabilities");
    }
}
