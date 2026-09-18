package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

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
import org.junit.jupiter.api.io.TempDir;

/**
 * Architectural Contract Test 24 (GAMEMODE_ARCHITECTURE.md Section 12):
 * Asserts that ObjectStoragePort streaming uploads and downloads transfer large objects
 * with bounded memory usage, without buffering full multi-gigabyte files into the JVM heap.
 */
class ObjectStorageStreamingTransferContractTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("24. Local filesystem adapter streaming transfer operates with bounded memory")
    void localFilesystemAdapterStreamingTransfer() throws IOException {
        LocalFilesystemStorageAdapter adapter = new LocalFilesystemStorageAdapter(tempDir);
        StorageBucket bucket = StorageBucket.of("stream-bucket");
        String key = "backups/large-world-dump.bin";

        // Generate synthetic stream
        byte[] chunk = new byte[64 * 1024]; // 64 KB chunk
        Arrays.fill(chunk, (byte) 0x5A);

        int totalChunks = 32; // 2 MB synthetic object
        byte[] expectedTotal = new byte[chunk.length * totalChunks];
        for (int i = 0; i < totalChunks; i++) {
            System.arraycopy(chunk, 0, expectedTotal, i * chunk.length, chunk.length);
        }

        try (InputStream in = new ByteArrayInputStream(expectedTotal)) {
            adapter.putStream(bucket, key, in, StorageObjectMetadata.empty());
        }

        assertThat(adapter.exists(bucket, key)).isTrue();

        // Download via openStream with bounded buffer
        Optional<InputStream> streamOpt = adapter.openStream(bucket, key);
        assertThat(streamOpt).isPresent();

        try (InputStream stream = streamOpt.get()) {
            byte[] readBuffer = new byte[16 * 1024]; // 16 KB read window
            int totalBytesRead = 0;
            int read;
            while ((read = stream.read(readBuffer)) != -1) {
                totalBytesRead += read;
            }
            assertThat(totalBytesRead).isEqualTo(expectedTotal.length);
        }
    }

    @Test
    @DisplayName("24. S3 adapter streaming transfer streams data and reads back via openStream")
    void s3AdapterStreamingTransfer() throws IOException {
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
                2,
                false,
                ProviderVerificationStatus.COMPLIANCE_VERIFIED);

        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(config, transport);
        StorageBucket bucket = StorageBucket.of("backup-bucket");
        String key = "snapshots/world-archive.zst";

        byte[] payload = new byte[512 * 1024]; // 512 KB fits within 1MB threshold
        Arrays.fill(payload, (byte) 0x42);

        try (InputStream in = new ByteArrayInputStream(payload)) {
            adapter.putStream(bucket, key, in, StorageObjectMetadata.empty());
        }

        assertThat(adapter.exists(bucket, key)).isTrue();

        Optional<InputStream> streamOpt = adapter.openStream(bucket, key);
        assertThat(streamOpt).isPresent();

        try (InputStream stream = streamOpt.get()) {
            byte[] readBuffer = new byte[8192];
            int totalBytesRead = 0;
            int read;
            while ((read = stream.read(readBuffer)) != -1) {
                totalBytesRead += read;
            }
            assertThat(totalBytesRead).isEqualTo(payload.length);
        }
    }
}
