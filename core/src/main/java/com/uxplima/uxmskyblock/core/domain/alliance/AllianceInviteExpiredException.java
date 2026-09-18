package com.uxplima.uxmskyblock.core.domain.alliance;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when attempting to accept an alliance invite that has already expired.
 */
public final class AllianceInviteExpiredException extends RuntimeException {

    private final IslandId senderIslandId;
    private final IslandId targetIslandId;
    private final Instant expiredAt;

    public AllianceInviteExpiredException(IslandId senderIslandId, IslandId targetIslandId, Instant expiredAt) {
        super("Alliance invite from " + Objects.requireNonNull(senderIslandId, "senderIslandId")
                + " to " + Objects.requireNonNull(targetIslandId, "targetIslandId")
                + " expired at " + Objects.requireNonNull(expiredAt, "expiredAt"));
        this.senderIslandId = senderIslandId;
        this.targetIslandId = targetIslandId;
        this.expiredAt = expiredAt;
    }

    public IslandId senderIslandId() {
        return senderIslandId;
    }

    public IslandId targetIslandId() {
        return targetIslandId;
    }

    public Instant expiredAt() {
        return expiredAt;
    }
}
