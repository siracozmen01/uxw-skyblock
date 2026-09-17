package com.uxplima.uxmskyblock.core.domain.storage;

import java.util.Locale;
import java.util.Objects;

/**
 * Value object representing an isolated object storage bucket or root namespace.
 */
public record StorageBucket(String name) {

    public static final StorageBucket DEFAULT_BACKUPS = StorageBucket.of("uxm-skyblock-backups");

    public StorageBucket {
        Objects.requireNonNull(name, "name");
        name = name.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Bucket name cannot be empty");
        }
    }

    public static StorageBucket of(String name) {
        return new StorageBucket(name);
    }
}
