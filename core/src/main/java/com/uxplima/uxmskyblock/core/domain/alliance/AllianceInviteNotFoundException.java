package com.uxplima.uxmskyblock.core.domain.alliance;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when an alliance invite cannot be located.
 */
public final class AllianceInviteNotFoundException extends RuntimeException {

    private final IslandId senderIslandId;
    private final IslandId targetIslandId;

    public AllianceInviteNotFoundException(IslandId senderIslandId, IslandId targetIslandId) {
        super("No alliance invite found from island " + Objects.requireNonNull(senderIslandId, "senderIslandId")
                + " to island " + Objects.requireNonNull(targetIslandId, "targetIslandId"));
        this.senderIslandId = senderIslandId;
        this.targetIslandId = targetIslandId;
    }

    public IslandId senderIslandId() {
        return senderIslandId;
    }

    public IslandId targetIslandId() {
        return targetIslandId;
    }
}
