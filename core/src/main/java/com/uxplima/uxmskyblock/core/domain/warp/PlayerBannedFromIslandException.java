package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

public class PlayerBannedFromIslandException extends RuntimeException {

    private final IslandId islandId;
    private final PlayerUuid playerUuid;
    private final @Nullable String reason;

    public PlayerBannedFromIslandException(IslandId islandId, PlayerUuid playerUuid, @Nullable String reason) {
        super("Player " + playerUuid + " is banned from island " + islandId
                + (reason != null && !reason.isBlank() ? ": " + reason : ""));
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        this.reason = reason;
    }

    public IslandId islandId() {
        return islandId;
    }

    public PlayerUuid playerUuid() {
        return playerUuid;
    }

    public @Nullable String reason() {
        return reason;
    }
}
