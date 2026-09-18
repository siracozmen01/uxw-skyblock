package com.uxplima.uxmskyblock.core.domain.freeze;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import org.jspecify.annotations.Nullable;

/**
 * Audit snapshot of an island's administrative freeze status.
 */
public record IslandFreezeRecord(
        IslandId islandId,
        AdministrativeState administrativeState,
        @Nullable String freezeReason,
        @Nullable String actor,
        Instant updatedAt) {

    public IslandFreezeRecord {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(administrativeState, "administrativeState must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static IslandFreezeRecord normal(IslandId islandId, Instant updatedAt) {
        return new IslandFreezeRecord(islandId, AdministrativeState.NORMAL, null, null, updatedAt);
    }

    public static IslandFreezeRecord frozen(
            IslandId islandId, String freezeReason, @Nullable String actor, Instant updatedAt) {
        Objects.requireNonNull(freezeReason, "freezeReason must not be null");
        return new IslandFreezeRecord(islandId, AdministrativeState.FROZEN, freezeReason, actor, updatedAt);
    }

    public boolean isFrozen() {
        return administrativeState == AdministrativeState.FROZEN;
    }
}
