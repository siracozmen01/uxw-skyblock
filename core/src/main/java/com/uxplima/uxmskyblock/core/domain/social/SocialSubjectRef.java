package com.uxplima.uxmskyblock.core.domain.social;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Universally identifiable reference to a social discoverable subject.
 * Keyed by namespaced type identifier and stable key (e.g. ("uxm:island", islandUuid)).
 */
public record SocialSubjectRef(String typeId, String key) {

    public static final String ISLAND_TYPE = "uxm:island";

    public SocialSubjectRef {
        Objects.requireNonNull(typeId, "typeId cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        if (typeId.isBlank()) {
            throw new IllegalArgumentException("typeId cannot be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key cannot be blank");
        }
    }

    public static SocialSubjectRef island(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId cannot be null");
        return new SocialSubjectRef(ISLAND_TYPE, islandId.value().toString());
    }
}
