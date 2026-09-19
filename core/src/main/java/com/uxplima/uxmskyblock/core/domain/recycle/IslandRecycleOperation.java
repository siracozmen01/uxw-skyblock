package com.uxplima.uxmskyblock.core.domain.recycle;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise audit and transactional record tracking the multi-step lifecycle
 * of an island recycle operation.
 */
public record IslandRecycleOperation(
        String operationId,
        IslandId islandId,
        PlayerUuid initiatorUuid,
        long targetSlot,
        IslandRecycleState state,
        @Nullable String backupPath,
        @Nullable String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public IslandRecycleOperation {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(initiatorUuid, "initiatorUuid must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
