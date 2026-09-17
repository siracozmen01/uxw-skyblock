package com.uxplima.uxmskyblock.core.domain.backup;

import java.util.Objects;

/**
 * Immutable descriptor for an artifact file belonging to a backup generation.
 *
 * @param filename relative file path / name
 * @param sizeBytes size in bytes
 * @param sha256Checksum hexadecimal SHA-256 hash string
 */
public record BackupArtifact(String filename, long sizeBytes, String sha256Checksum) {

    public BackupArtifact {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(sha256Checksum, "sha256Checksum");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative: " + sizeBytes);
        }
    }
}
