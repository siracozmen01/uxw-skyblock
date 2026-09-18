package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Tests 45, 46, 47, 48 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Verifies provider compatibility claims, real-endpoint requirements, emulator limits,
 * and fail-closed strict verification policies.
 */
class ProviderVerificationPolicyContractTest {

    @Nested
    @DisplayName("45. ProviderCompatibilityCannotBeClaimedBeforeSuitePassesTest")
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

    @Nested
    @DisplayName("46. AwsCompatibilityRequiresRealProviderEndpointTest")
    class AwsCompatibilityRequiresRealProviderEndpointTest {

        @Test
        @DisplayName("46. Passing against emulator cannot upgrade AWS S3 to COMPLIANCE_VERIFIED")
        void emulatorCannotUpgradeAwsS3ToComplianceVerified() {
            // Emulator passed = true, real live suite passed = false
            ProviderVerificationStatus status =
                    ProviderVerificationPolicy.promote(S3ProviderTarget.AWS_S3, true, false);

            assertThat(status).isEqualTo(ProviderVerificationStatus.EMULATOR_VERIFIED);
            assertThat(status).isNotEqualTo(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

            // Under strict mode, EMULATOR_VERIFIED is insufficient for AWS S3
            S3StorageConfiguration config = S3StorageConfiguration.createDefault(
                            URI.create("https://s3.amazonaws.com"),
                            "us-east-1",
                            StorageBucket.of("prod-bucket"),
                            S3Credentials.of("key", "secret"),
                            S3AddressingMode.PATH_STYLE,
                            S3ProviderTarget.AWS_S3)
                    .withVerificationStatus(status);

            S3StorageConfiguration strictConfig = new S3StorageConfiguration(
                    config.endpoint(),
                    config.region(),
                    config.bucket(),
                    config.credentials(),
                    config.addressingMode(),
                    config.providerTarget(),
                    config.pathPrefix(),
                    config.multipartThresholdBytes(),
                    config.partSizeBytes(),
                    config.maxRetries(),
                    true,
                    status);

            assertThatThrownBy(() -> ProviderVerificationPolicy.validateVerificationStatus(strictConfig))
                    .isInstanceOf(StorageProviderUnverifiedException.class)
                    .hasMessageContaining("requires COMPLIANCE_VERIFIED status");
        }
    }

    @Nested
    @DisplayName("47. R2CompatibilityRequiresRealProviderEndpointTest")
    class R2CompatibilityRequiresRealProviderEndpointTest {

        @Test
        @DisplayName("47. Passing against emulator cannot upgrade Cloudflare R2 to COMPLIANCE_VERIFIED")
        void emulatorCannotUpgradeR2ToComplianceVerified() {
            ProviderVerificationStatus status =
                    ProviderVerificationPolicy.promote(S3ProviderTarget.CLOUDFLARE_R2, true, false);

            assertThat(status).isEqualTo(ProviderVerificationStatus.EMULATOR_VERIFIED);
            assertThat(status).isNotEqualTo(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

            // Live execution promotes to COMPLIANCE_VERIFIED
            ProviderVerificationStatus liveStatus =
                    ProviderVerificationPolicy.promote(S3ProviderTarget.CLOUDFLARE_R2, true, true);
            assertThat(liveStatus).isEqualTo(ProviderVerificationStatus.COMPLIANCE_VERIFIED);
        }
    }

    @Nested
    @DisplayName("48. EmulatorCannotPromoteProviderToVerifiedTest")
    class EmulatorCannotPromoteProviderToVerifiedTest {

        @Test
        @DisplayName("48. Passing emulator verifies generic adapter but not production cloud targets")
        void passingEmulatorVerifiesGenericOnly() {
            // Generic S3 target with emulator passed
            ProviderVerificationStatus genericStatus =
                    ProviderVerificationPolicy.promote(S3ProviderTarget.GENERIC_S3, true, false);
            assertThat(genericStatus).isEqualTo(ProviderVerificationStatus.EMULATOR_VERIFIED);

            S3StorageConfiguration genericConfig = new S3StorageConfiguration(
                    URI.create("http://localhost:9000"),
                    "us-east-1",
                    StorageBucket.of("generic"),
                    S3Credentials.of("k", "s"),
                    S3AddressingMode.PATH_STYLE,
                    S3ProviderTarget.GENERIC_S3,
                    null,
                    5 * 1024 * 1024,
                    5 * 1024 * 1024,
                    3,
                    true,
                    genericStatus);

            // Generic S3 succeeds under strict verification with EMULATOR_VERIFIED
            assertThatCode(() -> ProviderVerificationPolicy.validateVerificationStatus(genericConfig))
                    .doesNotThrowAnyException();

            // However, AWS S3 and R2 are strictly rejected with EMULATOR_VERIFIED under strict mode
            S3StorageConfiguration awsConfig = new S3StorageConfiguration(
                    URI.create("https://s3.amazonaws.com"),
                    "us-east-1",
                    StorageBucket.of("aws"),
                    S3Credentials.of("k", "s"),
                    S3AddressingMode.PATH_STYLE,
                    S3ProviderTarget.AWS_S3,
                    null,
                    5 * 1024 * 1024,
                    5 * 1024 * 1024,
                    3,
                    true,
                    genericStatus);

            assertThatThrownBy(() -> ProviderVerificationPolicy.validateVerificationStatus(awsConfig))
                    .isInstanceOf(StorageProviderUnverifiedException.class);
        }
    }
}
