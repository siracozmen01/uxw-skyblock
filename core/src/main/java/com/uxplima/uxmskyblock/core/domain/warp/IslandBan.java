package com.uxplima.uxmskyblock.core.domain.warp;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Domain record representing an active visitor ban from an island.
 */
public record IslandBan(
        IslandId islandId,
        PlayerUuid bannedPlayerUuid,
        ProfileId bannedByProfileId,
        @Nullable String reason,
        Instant createdAt) {

    public IslandBan {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(bannedPlayerUuid, "bannedPlayerUuid must not be null");
        Objects.requireNonNull(bannedByProfileId, "bannedByProfileId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
