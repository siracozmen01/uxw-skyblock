package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 28 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that access keys, secret keys, and session tokens are strictly redacted
 * from diagnostic logs, exception traces, and manifest serialization.
 */
class ObjectStorageCredentialRedactionTest {

    @Test
    @DisplayName("28. Credentials toString redacts access key, secret key, and session token")
    void credentialsToStringRedactsSensitiveInformation() {
        String accessKey = "AKIAIOSFODNN7EXAMPLE";
        String secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String sessionToken = "AQoDYXdzEJr1EXAMPLE1234567890";

        S3Credentials credentials = S3Credentials.of(accessKey, secretKey, sessionToken);
        String stringRep = credentials.toString();

        assertThat(stringRep)
                .doesNotContain(accessKey)
                .doesNotContain(secretKey)
                .doesNotContain(sessionToken)
                .contains("REDACTED");
    }

    @Test
    @DisplayName("28. StorageConfiguration toString redacts nested credentials")
    void configurationToStringRedactsCredentials() {
        String accessKey = "SUPER_SECRET_ACCESS_KEY";
        String secretKey = "SUPER_SECRET_SECRET_KEY";

        S3StorageConfiguration config = S3StorageConfiguration.createDefault(
                URI.create("https://s3.eu-central-1.amazonaws.com"),
                "eu-central-1",
                StorageBucket.of("secure-bucket"),
                S3Credentials.of(accessKey, secretKey),
                S3AddressingMode.VIRTUAL_HOSTED,
                S3ProviderTarget.AWS_S3);

        String configString = config.toString();

        assertThat(configString)
                .doesNotContain(accessKey)
                .doesNotContain(secretKey)
                .contains("REDACTED");
    }
}
