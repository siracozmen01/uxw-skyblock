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
 * Architectural Contract Test 47 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that S3CompatibleProviderR2CompatibilityContractTest requires execution against
 * a real Cloudflare R2 endpoint/account, and that passing against an emulator cannot upgrade Cloudflare R2 to verified status.
 */
class R2CompatibilityRequiresRealProviderEndpointTest {

    @Test
    @DisplayName("47. Passing against emulator cannot upgrade Cloudflare R2 to COMPLIANCE_VERIFIED")
    void emulatorCannotUpgradeR2ToComplianceVerified() {
        ProviderVerificationStatus status =
                ProviderVerificationPolicy.promote(S3ProviderTarget.CLOUDFLARE_R2, true, false);

        assertThat(status).isEqualTo(ProviderVerificationStatus.EMULATOR_VERIFIED);
        assertThat(status).isNotEqualTo(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3StorageConfiguration strictConfig = new S3StorageConfiguration(
                URI.create("https://account.r2.cloudflarestorage.com"),
                "auto",
                StorageBucket.of("r2-bucket"),
                S3Credentials.of("key", "secret"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.CLOUDFLARE_R2,
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
