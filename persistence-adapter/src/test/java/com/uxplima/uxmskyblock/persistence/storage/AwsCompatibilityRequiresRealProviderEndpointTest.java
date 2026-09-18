package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationPolicy;
import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageProviderUnverifiedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 46 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that S3CompatibleProviderAwsCompatibilityContractTest requires execution against
 * a real AWS S3 endpoint/account, and that passing against an emulator cannot upgrade AWS S3 to verified status.
 */
class AwsCompatibilityRequiresRealProviderEndpointTest {

    @Test
    @DisplayName("46. Passing against emulator cannot upgrade AWS S3 to COMPLIANCE_VERIFIED")
    void emulatorCannotUpgradeAwsS3ToComplianceVerified() {
        ProviderVerificationStatus status = ProviderVerificationPolicy.promote(S3ProviderTarget.AWS_S3, true, false);

        assertThat(status).isEqualTo(ProviderVerificationStatus.EMULATOR_VERIFIED);
        assertThat(status).isNotEqualTo(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3StorageConfiguration strictConfig = new S3StorageConfiguration(
                URI.create("https://s3.amazonaws.com"),
                "us-east-1",
                StorageBucket.of("prod-bucket"),
                S3Credentials.of("key", "secret"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.AWS_S3,
                null,
                5 * 1024 * 1024,
                5 * 1024 * 1024,
                3,
                true,
                status);

        assertThatThrownBy(() -> ProviderVerificationPolicy.validateVerificationStatus(strictConfig))
                .isInstanceOf(StorageProviderUnverifiedException.class)
                .hasMessageContaining("requires COMPLIANCE_VERIFIED status");
    }
}
