package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
 * Architectural Contract Test 29 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that the S3-compatible adapter negotiates AWS S3 endpoints, headers,
 * addressing modes, and multipart upload protocols conforming to verified AWS S3 semantics.
 */
class S3CompatibleProviderAwsCompatibilityContractTest {

    @Test
    @DisplayName("29. AWS S3 negotiations conform to SigV4 and addressing modes")
    void awsS3NegotiatesAddressingAndSigV4Headers() {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        StorageBucket bucket = StorageBucket.of("skyblock-production-backups");

        // 1. Path Style Addressing
        S3StorageConfiguration pathConfig = new S3StorageConfiguration(
                URI.create("https://s3.us-east-1.amazonaws.com"),
                "us-east-1",
                bucket,
                S3Credentials.of("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.AWS_S3,
                "global-backups",
                1024 * 1024,
                1024 * 1024,
                3,
                false,
                ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter pathAdapter = new S3ObjectStorageAdapter(pathConfig, transport);
        pathAdapter.putObject(bucket, "manifest.json", new byte[] {1, 2, 3}, StorageObjectMetadata.empty());

        List<S3HttpRequest> requests = transport.recordedRequests();
        assertThat(requests).isNotEmpty();
        S3HttpRequest putReq = requests.get(0);

        // Verify Path Style URI: /bucket/prefix/key
        assertThat(putReq.uri().getPath()).isEqualTo("/skyblock-production-backups/global-backups/manifest.json");

        // Verify AWS SigV4 Headers
        assertThat(putReq.headers()).containsKey("Host");
        assertThat(putReq.headers().get("Host")).contains("s3.us-east-1.amazonaws.com");
        assertThat(putReq.headers()).containsKey("x-amz-date");
        assertThat(putReq.headers()).containsKey("x-amz-content-sha256");
        assertThat(putReq.headers()).containsKey("Authorization");
        String authHeader =
                Objects.requireNonNull(putReq.headers().get("Authorization")).get(0);
        assertThat(authHeader).startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/");
        assertThat(authHeader).contains("/us-east-1/s3/aws4_request");
        assertThat(authHeader).contains("SignedHeaders=");
        assertThat(authHeader).contains("Signature=");

        // 2. Virtual Hosted Style Addressing
        S3StorageConfiguration vhostConfig = new S3StorageConfiguration(
                URI.create("https://s3.us-east-1.amazonaws.com"),
                "us-east-1",
                bucket,
                S3Credentials.of("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
                S3AddressingMode.VIRTUAL_HOSTED,
                S3ProviderTarget.AWS_S3,
                null,
                1024 * 1024,
                1024 * 1024,
                3,
                false,
                ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter vhostAdapter = new S3ObjectStorageAdapter(vhostConfig, transport);
        vhostAdapter.exists(bucket, "root-manifest.json");

        S3HttpRequest headReq =
                transport.recordedRequests().get(transport.recordedRequests().size() - 1);
        assertThat(headReq.uri().getHost()).isEqualTo("skyblock-production-backups.s3.us-east-1.amazonaws.com");
        assertThat(headReq.uri().getPath()).isEqualTo("/root-manifest.json");
    }

    @Test
    @DisplayName("29. AWS S3 multipart upload protocol executes correct sequence")
    void awsS3MultipartUploadProtocolSequence() throws Exception {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        StorageBucket bucket = StorageBucket.of("backup-bucket");

        S3StorageConfiguration config = new S3StorageConfiguration(
                URI.create("https://s3.us-east-1.amazonaws.com"),
                "us-east-1",
                bucket,
                S3Credentials.of("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.AWS_S3,
                null,
                1024 * 1024, // 1 MB threshold
                1024 * 1024, // 1 MB part size
                3,
                false,
                ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, transport);

        // 2.5 MB payload requires: Initiate -> Part 1 -> Part 2 -> Part 3 -> Complete
        byte[] payload = new byte[(int) (2.5 * 1024 * 1024)];
        try (InputStream in = new ByteArrayInputStream(payload)) {
            adapter.putStream(bucket, "island-snapshot.schem.zst", in, StorageObjectMetadata.empty());
        }

        List<S3HttpRequest> reqs = transport.recordedRequests();
        assertThat(reqs).hasSize(5);

        // 1. Initiate (POST ?uploads=)
        assertThat(reqs.get(0).method()).isEqualTo("POST");
        assertThat(reqs.get(0).uri().getQuery()).contains("uploads=");

        // 2. Part 1 (PUT ?partNumber=1&uploadId=...)
        assertThat(reqs.get(1).method()).isEqualTo("PUT");
        assertThat(reqs.get(1).uri().getQuery()).contains("partNumber=1");

        // 3. Part 2 (PUT ?partNumber=2&uploadId=...)
        assertThat(reqs.get(2).method()).isEqualTo("PUT");
        assertThat(reqs.get(2).uri().getQuery()).contains("partNumber=2");

        // 4. Part 3 (PUT ?partNumber=3&uploadId=...)
        assertThat(reqs.get(3).method()).isEqualTo("PUT");
        assertThat(reqs.get(3).uri().getQuery()).contains("partNumber=3");

        // 5. Complete (POST ?uploadId=...)
        assertThat(reqs.get(4).method()).isEqualTo("POST");
        assertThat(reqs.get(4).uri().getQuery()).contains("uploadId=");
        assertThat(new String(reqs.get(4).body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("<CompleteMultipartUpload>");

        assertThat(adapter.providerId()).isEqualTo(ObjectStorageProviderId.AWS_S3);
        assertThat(adapter.capabilities())
                .contains(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.OBJECT_LOCK,
                        ObjectStorageCapability.NATIVE_OBJECT_VERSIONING);
    }
}
