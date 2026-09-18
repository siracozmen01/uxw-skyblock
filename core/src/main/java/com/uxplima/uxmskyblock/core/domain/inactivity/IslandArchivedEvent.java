package com.uxplima.uxmskyblock.core.domain.inactivity;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Domain event emitted when an island is archived due to total team abandonment.
 */
public record IslandArchivedEvent(IslandId islandId, String reason, Instant occurredAt) {

    public IslandArchivedEvent {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
