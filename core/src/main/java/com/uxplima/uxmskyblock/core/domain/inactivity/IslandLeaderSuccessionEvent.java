package com.uxplima.uxmskyblock.core.domain.inactivity;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain event emitted when an island's leadership is automatically transferred due to owner inactivity.
 */
public record IslandLeaderSuccessionEvent(
        IslandId islandId,
        ProfileId formerOwnerProfileId,
        ProfileId newOwnerProfileId,
        FormerOwnerAction formerOwnerAction,
        Instant occurredAt) {

    public IslandLeaderSuccessionEvent {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(formerOwnerProfileId, "formerOwnerProfileId must not be null");
        Objects.requireNonNull(newOwnerProfileId, "newOwnerProfileId must not be null");
        Objects.requireNonNull(formerOwnerAction, "formerOwnerAction must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
