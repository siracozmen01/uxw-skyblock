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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 48 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that passing test suites on local emulators or compatible mock servers
 * verifies only generic S3 adapter behavior and strictly cannot promote AWS S3 or Cloudflare R2
 * to verified compatibility targets.
 */
class EmulatorCannotPromoteProviderToVerifiedTest {

    @Test
    @DisplayName("48. Passing emulator verifies generic adapter but not production cloud targets")
    void passingEmulatorVerifiesGenericOnly() {
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

        // Generic S3 is allowed with EMULATOR_VERIFIED
        assertThatCode(() -> ProviderVerificationPolicy.validateVerificationStatus(genericConfig))
                .doesNotThrowAnyException();

        // AWS S3 fails closed
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
