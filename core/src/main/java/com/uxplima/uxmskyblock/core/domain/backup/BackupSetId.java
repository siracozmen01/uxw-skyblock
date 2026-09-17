package com.uxplima.uxmskyblock.core.domain.backup;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a durable backup generation set.
 */
public record BackupSetId(UUID value) implements Comparable<BackupSetId> {

    public BackupSetId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static BackupSetId of(UUID value) {
        return new BackupSetId(value);
    }

    public static BackupSetId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new BackupSetId(UUID.fromString(value));
    }

    public static BackupSetId random() {
        return new BackupSetId(UUID.randomUUID());
    }

    @Override
    public int compareTo(BackupSetId other) {
        int msb = Long.compareUnsigned(this.value.getMostSignificantBits(), other.value.getMostSignificantBits());
        if (msb != 0) {
            return msb;
        }
        return Long.compareUnsigned(this.value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
