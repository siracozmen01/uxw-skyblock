package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability;
import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId;
import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3HttpRequest;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 30 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that the S3-compatible adapter negotiates Cloudflare R2 endpoints,
 * authentication, and supported capabilities conforming to verified R2 semantics.
 */
class S3CompatibleProviderR2CompatibilityContractTest {

    @Test
    @DisplayName("30. Cloudflare R2 endpoint negotiation and SigV4 authentication")
    void cloudflareR2NegotiatesEndpointsAndAuth() {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        StorageBucket bucket = StorageBucket.of("r2-backup-bucket");

        S3StorageConfiguration r2Config = new S3StorageConfiguration(
                URI.create("https://0123456789abcdef.r2.cloudflarestorage.com"),
                "auto", // R2 standard auto region
                bucket,
                S3Credentials.of("r2-access-token-id", "r2-secret-token-key"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.CLOUDFLARE_R2,
                "skyblock-v1",
                5 * 1024 * 1024,
                5 * 1024 * 1024,
                3,
                false,
                ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(r2Config, transport);

        // Put object
        adapter.putObject(bucket, "test-marker.json", new byte[] {42}, StorageObjectMetadata.empty());

        List<S3HttpRequest> requests = transport.recordedRequests();
        assertThat(requests).isNotEmpty();
        S3HttpRequest putReq = requests.get(0);

        // Verify Host & URI path
        assertThat(putReq.uri().getHost()).isEqualTo("0123456789abcdef.r2.cloudflarestorage.com");
        assertThat(putReq.uri().getPath()).isEqualTo("/r2-backup-bucket/skyblock-v1/test-marker.json");

        // Verify SigV4 header with "auto" region
        assertThat(putReq.headers()).containsKey("Authorization");
        String authHeader =
                Objects.requireNonNull(putReq.headers().get("Authorization")).get(0);
        assertThat(authHeader).startsWith("AWS4-HMAC-SHA256 Credential=r2-access-token-id/");
        assertThat(authHeader).contains("/auto/s3/aws4_request");

        // Verify Provider ID & Capabilities
        assertThat(adapter.providerId()).isEqualTo(ObjectStorageProviderId.CLOUDFLARE_R2);
        assertThat(adapter.capabilities())
                .contains(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.STREAMING_TRANSFER,
                        ObjectStorageCapability.RANGE_READ);
    }
}
