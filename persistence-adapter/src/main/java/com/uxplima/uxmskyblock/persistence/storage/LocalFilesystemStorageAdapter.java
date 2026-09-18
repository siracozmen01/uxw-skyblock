package com.uxplima.uxmskyblock.persistence.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;

/**
 * Local filesystem-backed object storage adapter featuring atomic file writes and path traversal guards.
 */
public final class LocalFilesystemStorageAdapter implements ObjectStoragePort {

    private final Path rootDir;

    public LocalFilesystemStorageAdapter(Path rootDir) {
        this.rootDir =
                Objects.requireNonNull(rootDir, "rootDir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize object storage root: " + rootDir, e);
        }
    }

    private Path resolveSafePath(StorageBucket bucket, String objectKey) {
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(objectKey, "objectKey");

        String sanitizedKey = objectKey.replace('\\', '/');
        if (sanitizedKey.startsWith("/")) {
            sanitizedKey = sanitizedKey.substring(1);
        }

        Path bucketDir = rootDir.resolve(bucket.name()).normalize();
        Path targetPath = bucketDir.resolve(sanitizedKey).normalize();

        if (!targetPath.startsWith(bucketDir) || !targetPath.startsWith(rootDir)) {
            throw new IllegalArgumentException("Path traversal attempt detected: " + objectKey);
        }

        return targetPath;
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public void putObject(StorageBucket bucket, String objectKey, byte[] data, StorageObjectMetadata metadata) {
        Objects.requireNonNull(data, "data");
        Path target = resolveSafePath(bucket, objectKey);

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path tempFile = Files.createTempFile(parent, "obj-", ".tmp");
            try {
                Files.write(tempFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveUnsupported) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist object: " + objectKey + " in bucket " + bucket.name(), e);
        }
    }

    @Override
    public Optional<byte[]> getObject(StorageBucket bucket, String objectKey) {
        Path target = resolveSafePath(bucket, objectKey);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read object: " + objectKey + " from bucket " + bucket.name(), e);
        }
    }

    @Override
    public boolean exists(StorageBucket bucket, String objectKey) {
        Path target = resolveSafePath(bucket, objectKey);
        return Files.isRegularFile(target);
    }

    @Override
    public void deleteObject(StorageBucket bucket, String objectKey) {
        Path target = resolveSafePath(bucket, objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete object: " + objectKey + " from bucket " + bucket.name(), e);
        }
    }

    @Override
    public List<String> listObjects(StorageBucket bucket, String prefix) {
        Path bucketDir = rootDir.resolve(bucket.name()).normalize();
        if (!Files.isDirectory(bucketDir)) {
            return List.of();
        }

        String rawPrefix = prefix.replace('\\', '/');
        String normalizedPrefix = rawPrefix.startsWith("/") ? rawPrefix.substring(1) : rawPrefix;

        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(bucketDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = bucketDir.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(normalizedPrefix)) {
                    results.add(relative);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list objects in bucket " + bucket.name(), e);
        }

        return List.copyOf(results);
    }

    @Override
    public Optional<StorageObjectMetadata> getMetadata(StorageBucket bucket, String objectKey) {
        Path target = resolveSafePath(bucket, objectKey);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }

        try {
            byte[] data = Files.readAllBytes(target);
            Instant lastModified = Files.getLastModifiedTime(target).toInstant();
            return Optional.of(StorageObjectMetadata.of(
                    "application/octet-stream", data.length, computeSha256(data), lastModified));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read metadata for " + objectKey, e);
        }
    }

    @Override
    public Optional<java.io.InputStream> openStream(StorageBucket bucket, String objectKey) {
        Path target = resolveSafePath(bucket, objectKey);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(target));
        } catch (IOException e) {
            throw new RuntimeException("Failed to open stream for " + objectKey, e);
        }
    }

    @Override
    public void putStream(
            StorageBucket bucket, String objectKey, java.io.InputStream inputStream, StorageObjectMetadata metadata) {
        Objects.requireNonNull(inputStream, "inputStream");
        Path target = resolveSafePath(bucket, objectKey);

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path tempFile = Files.createTempFile(parent, "stream-obj-", ".tmp");
            try {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicMoveUnsupported) {
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist streaming object: " + objectKey, e);
        }
    }

    @Override
    public java.util.Set<com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability> capabilities() {
        return java.util.Set.of(
                com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability.STREAMING_TRANSFER,
                com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability.RANGE_READ,
                com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability.SERVER_SIDE_COPY,
                com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability.CHECKSUM_ALGORITHMS);
    }

    @Override
    public com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId providerId() {
        return com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId.LOCAL_FS;
    }
}
