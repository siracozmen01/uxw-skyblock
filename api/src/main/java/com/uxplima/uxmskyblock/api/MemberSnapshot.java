package com.uxplima.uxmskyblock.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public snapshot of an island member.
 */
public record MemberSnapshot(UUID playerUuid, String role, Instant joinedAt) {

    public MemberSnapshot {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(joinedAt, "joinedAt must not be null");
    }
}
