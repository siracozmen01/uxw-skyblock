package com.uxplima.uxmskyblock.core.domain.freeze;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;

/**
 * Domain event published when an island's economic state transitions.
 */
public record IslandEconomicStateChangedEvent(
        EventId eventId, IslandId islandId, EconomicState previousState, EconomicState newState, Instant timestamp) {

    public IslandEconomicStateChangedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(previousState, "previousState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static IslandEconomicStateChangedEvent create(
            IslandId islandId, EconomicState previousState, EconomicState newState, Instant timestamp) {
        return new IslandEconomicStateChangedEvent(EventId.random(), islandId, previousState, newState, timestamp);
    }
}
