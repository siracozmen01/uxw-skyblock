package com.uxplima.uxmskyblock.core.domain.island;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Immutable membership record linking a player profile to an island role.
 */
public record IslandMember(PlayerUuid playerUuid, ProfileId profileId, IslandRole role, Instant joinedAt) {

    public IslandMember {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(joinedAt, "joinedAt must not be null");
    }

    public IslandMember withRole(IslandRole newRole) {
        Objects.requireNonNull(newRole, "newRole must not be null");
        return new IslandMember(playerUuid, profileId, newRole, joinedAt);
    }
}
