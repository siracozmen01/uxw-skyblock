package com.uxplima.uxmskyblock.persistence.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemStorageAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should put, get, check existence, and delete objects")
    void testBasicCrud() {
        LocalFilesystemStorageAdapter adapter = new LocalFilesystemStorageAdapter(tempDir);
        StorageBucket bucket = StorageBucket.of("test-bucket");
        String key = "sub/dir/test.txt";
        byte[] payload = "Hello Skyblock Backup!".getBytes(StandardCharsets.UTF_8);

        assertThat(adapter.exists(bucket, key)).isFalse();
        assertThat(adapter.getObject(bucket, key)).isEmpty();

        adapter.putObject(bucket, key, payload, StorageObjectMetadata.empty());

        assertThat(adapter.exists(bucket, key)).isTrue();
        Optional<byte[]> retrieved = adapter.getObject(bucket, key);
        assertThat(retrieved).isPresent();
        assertThat(new String(retrieved.get(), StandardCharsets.UTF_8)).isEqualTo("Hello Skyblock Backup!");

        adapter.deleteObject(bucket, key);
        assertThat(adapter.exists(bucket, key)).isFalse();
        assertThat(adapter.getObject(bucket, key)).isEmpty();
    }

    @Test
    @DisplayName("Should compute correct metadata including SHA-256")
    void testMetadata() {
        LocalFilesystemStorageAdapter adapter = new LocalFilesystemStorageAdapter(tempDir);
        StorageBucket bucket = StorageBucket.of("meta-bucket");
        String key = "manifest.json";
        byte[] data = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);

        adapter.putObject(bucket, key, data, StorageObjectMetadata.empty());

        Optional<StorageObjectMetadata> metaOpt = adapter.getMetadata(bucket, key);
        assertThat(metaOpt).isPresent();
        StorageObjectMetadata meta = metaOpt.get();
        assertThat(meta.contentLength()).isEqualTo(data.length);
        assertThat(meta.sha256Checksum()).isNotBlank();
        assertThat(meta.lastModified()).isNotNull();
    }

    @Test
    @DisplayName("Should list objects by prefix")
    void testListObjects() {
        LocalFilesystemStorageAdapter adapter = new LocalFilesystemStorageAdapter(tempDir);
        StorageBucket bucket = StorageBucket.of("list-bucket");

        adapter.putObject(bucket, "islands/isl-1/backup-1/data.bin", new byte[] {1}, StorageObjectMetadata.empty());
        adapter.putObject(bucket, "islands/isl-1/backup-2/data.bin", new byte[] {2}, StorageObjectMetadata.empty());
        adapter.putObject(bucket, "islands/isl-2/backup-1/data.bin", new byte[] {3}, StorageObjectMetadata.empty());

        List<String> isl1Objects = adapter.listObjects(bucket, "islands/isl-1");
        assertThat(isl1Objects).hasSize(2);
        assertThat(isl1Objects).allMatch(s -> s.startsWith("islands/isl-1/"));

        List<String> allObjects = adapter.listObjects(bucket, "islands");
        assertThat(allObjects).hasSize(3);

        List<String> emptyObjects = adapter.listObjects(bucket, "nonexistent");
        assertThat(emptyObjects).isEmpty();
    }

    @Test
    @DisplayName("Should block path traversal attempts")
    void testPathTraversalGuards() {
        LocalFilesystemStorageAdapter adapter = new LocalFilesystemStorageAdapter(tempDir);
        StorageBucket bucket = StorageBucket.of("secure-bucket");

        assertThatThrownBy(
                        () -> adapter.putObject(bucket, "../escape.txt", new byte[] {0}, StorageObjectMetadata.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal");

        assertThatThrownBy(() -> adapter.getObject(bucket, "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal");

        assertThatThrownBy(() -> adapter.deleteObject(bucket, "sub/../../escape"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path traversal");
    }
}
