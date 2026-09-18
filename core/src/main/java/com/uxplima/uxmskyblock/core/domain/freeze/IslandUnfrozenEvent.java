package com.uxplima.uxmskyblock.core.domain.freeze;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Domain event published when an island is released from administrative quarantine.
 */
public record IslandUnfrozenEvent(EventId eventId, IslandId islandId, String actor, Instant timestamp) {

    public IslandUnfrozenEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static IslandUnfrozenEvent create(IslandId islandId, String actor, Instant timestamp) {
        return new IslandUnfrozenEvent(EventId.random(), islandId, actor, timestamp);
    }
}
