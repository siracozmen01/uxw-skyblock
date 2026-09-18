package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;

import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 25 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that an interrupted multipart object upload can safely abort or resume and retry
 * without leaking orphaned parts or corrupting the target object.
 */
class ObjectStorageMultipartRecoveryContractTest {

    @Test
    @DisplayName("25. Interrupted multipart upload aborts safely without orphaned parts and allows clean retry")
    void interruptedMultipartUploadAbortsAndRetriesCleanly() throws Exception {
        MockS3HttpTransport transport = new MockS3HttpTransport();
        S3StorageConfiguration config = new S3StorageConfiguration(
                URI.create("https://s3.us-east-1.amazonaws.com"),
                "us-east-1",
                StorageBucket.of("backup-bucket"),
                S3Credentials.of("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
                S3AddressingMode.PATH_STYLE,
                S3ProviderTarget.AWS_S3,
                null,
                1024 * 1024, // 1 MB threshold
                1024 * 1024, // 1 MB part size
                0, // 0 retries for deterministic failure trigger
                false,
                ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, transport);
        StorageBucket bucket = StorageBucket.of("backup-bucket");
        String key = "disaster/huge-db-dump.sql.zst";

        // 2.5 MB object (will require 3 parts: Part 1 = 1MB, Part 2 = 1MB, Part 3 = 0.5MB)
        byte[] payload = new byte[(int) (2.5 * 1024 * 1024)];
        Arrays.fill(payload, (byte) 0x7E);

        // 1. Inject failure on Part 2
        transport.setFailOnPartUpload(2);

        assertThatThrownBy(() -> {
                    try (InputStream in = new ByteArrayInputStream(payload)) {
                        adapter.putStream(bucket, key, in, StorageObjectMetadata.empty());
                    }
                })
                .hasRootCauseMessage("Simulated network failure on part 2");

        // 2. Verify abortMultipartUpload was triggered and no orphaned parts remain
        assertThat(transport.abortedUploads()).isNotEmpty();
        assertThat(transport.hasOrphanedMultipartUploads()).isFalse();
        assertThat(adapter.exists(bucket, key)).isFalse();

        // 3. Clear failure and retry upload
        transport.setFailOnPartUpload(-1); // Reset failure

        try (InputStream in = new ByteArrayInputStream(payload)) {
            adapter.putStream(bucket, key, in, StorageObjectMetadata.empty());
        }

        // 4. Verify retry succeeded completely without orphaned parts or corruption
        assertThat(adapter.exists(bucket, key)).isTrue();
        assertThat(transport.hasOrphanedMultipartUploads()).isFalse();
        byte[] retrieved = adapter.getObject(bucket, key).orElseThrow();
        assertThat(retrieved).isEqualTo(payload);
    }
}
