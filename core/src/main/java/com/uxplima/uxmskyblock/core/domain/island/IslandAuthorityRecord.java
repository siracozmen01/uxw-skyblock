package com.uxplima.uxmskyblock.core.domain.island;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Distributed single-writer authority lease record for an island.
 */
public record IslandAuthorityRecord(
        IslandId islandId,
        ServerNodeId authoritativeNode,
        long authorityEpoch,
        Instant leaseExpiresAt,
        Instant lastHeartbeatAt) {

    public IslandAuthorityRecord {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(authoritativeNode, "authoritativeNode must not be null");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt must not be null");
        if (authorityEpoch < 1) {
            throw new IllegalArgumentException("authorityEpoch must be positive: " + authorityEpoch);
        }
    }

    public boolean isExpired(Instant referenceTime) {
        Objects.requireNonNull(referenceTime, "referenceTime must not be null");
        return leaseExpiresAt.isBefore(referenceTime);
    }
}
