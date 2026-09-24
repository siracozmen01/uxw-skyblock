package com.uxplima.uxmskyblock.core.domain.inventory;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * A player's whole state, to be written by the node holding their session at this epoch, over the
 * version it last wrote. A write that carries this is refused unless all three still hold.
 */
public record PlayerStateWrite(
        PlayerUuid playerUuid,
        ServerNodeId node,
        long sessionEpoch,
        long expectedVersion,
        ProfileInventoryRecord state) {

    public PlayerStateWrite {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(state, "state");
    }

    /** The version the write leaves behind. */
    public long writtenVersion() {
        return expectedVersion + 1;
    }
}
