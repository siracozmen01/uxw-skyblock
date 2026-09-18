package com.uxplima.uxmskyblock.core.domain.freeze;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Domain event published when an island is placed under administrative quarantine.
 */
public record IslandFrozenEvent(EventId eventId, IslandId islandId, String reason, String actor, Instant timestamp) {

    public IslandFrozenEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static IslandFrozenEvent create(IslandId islandId, String reason, String actor, Instant timestamp) {
        return new IslandFrozenEvent(EventId.random(), islandId, reason, actor, timestamp);
    }
}
