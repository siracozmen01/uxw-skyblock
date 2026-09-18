package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageChecksumMismatchException;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 27 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that object transfers calculate and verify strong application-level SHA-256 checksums,
 * detecting single-bit corruptions independently of provider ETag formats.
 */
class ObjectStorageChecksumVerificationTest {

    @Test
    @DisplayName("27. PutObject detects checksum mismatch and fails closed")
    void putObjectDetectsChecksumMismatch() {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        S3StorageConfiguration config = S3StorageConfiguration.createDefault(
                        URI.create("https://s3.amazonaws.com"),
                        "us-east-1",
                        StorageBucket.of("backup-bucket"),
                        S3Credentials.of("access", "secret"),
                        S3AddressingMode.PATH_STYLE,
                        S3ProviderTarget.AWS_S3)
                .withVerificationStatus(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, transport);
        StorageBucket bucket = StorageBucket.of("backup-bucket");
        String key = "manifest.json";

        byte[] originalData = "{\"backup_set_id\":\"bk-1234\"}".getBytes(StandardCharsets.UTF_8);
        String correctSha256 = sha256Hex(originalData);

        // Corrupt single character in checksum
        String corruptedSha256 =
                correctSha256.substring(0, correctSha256.length() - 1) + (correctSha256.endsWith("a") ? "b" : "a");

        StorageObjectMetadata corruptedMetadata =
                StorageObjectMetadata.of("application/json", originalData.length, corruptedSha256, Instant.now());

        // Fails closed with StorageChecksumMismatchException
        assertThatThrownBy(() -> adapter.putObject(bucket, key, originalData, corruptedMetadata))
                .isInstanceOf(StorageChecksumMismatchException.class)
                .hasMessageContaining("does not match expected metadata checksum");

        // With correct checksum, passes cleanly
        StorageObjectMetadata validMetadata =
                StorageObjectMetadata.of("application/json", originalData.length, correctSha256, Instant.now());
        assertThatCode(() -> adapter.putObject(bucket, key, originalData, validMetadata))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("27. PutStream detects single-bit corruption in streaming transfers")
    void putStreamDetectsSingleBitCorruption() {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        S3StorageConfiguration config = S3StorageConfiguration.createDefault(
                        URI.create("https://s3.amazonaws.com"),
                        "us-east-1",
                        StorageBucket.of("backup-bucket"),
                        S3Credentials.of("access", "secret"),
                        S3AddressingMode.PATH_STYLE,
                        S3ProviderTarget.AWS_S3)
                .withVerificationStatus(ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, transport);
        StorageBucket bucket = StorageBucket.of("backup-bucket");
        String key = "corrupted-stream.bin";

        byte[] payload = new byte[100 * 1024]; // 100 KB
        payload[0] = 0x10;
        String validChecksum = sha256Hex(payload);

        // Corrupt first byte in the payload
        byte[] corruptedPayload = payload.clone();
        corruptedPayload[0] ^= 0x01; // flip single bit

        StorageObjectMetadata expectedMetadata =
                StorageObjectMetadata.of("application/octet-stream", payload.length, validChecksum, Instant.now());

        assertThatThrownBy(() -> {
                    try (InputStream in = new ByteArrayInputStream(corruptedPayload)) {
                        adapter.putStream(bucket, key, in, expectedMetadata);
                    }
                })
                .isInstanceOf(StorageChecksumMismatchException.class)
                .hasMessageContaining("does not match expected metadata checksum");
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
