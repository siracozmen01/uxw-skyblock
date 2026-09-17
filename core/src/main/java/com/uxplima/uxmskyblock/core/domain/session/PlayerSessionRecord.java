package com.uxplima.uxmskyblock.core.domain.session;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Pure domain record representing the state of a player session authority row in SQL storage.
 */
public record PlayerSessionRecord(
        PlayerUuid playerUuid,
        ProfileId activeProfileId,
        ServerNodeId authoritativeNode,
        long sessionEpoch,
        SessionState state,
        Instant leaseExpiresAt,
        long lastDurableInventoryVersion,
        @Nullable String handoffId,
        @Nullable ServerNodeId handoffTargetNode,
        @Nullable Instant handoffExpiresAt) {

    public PlayerSessionRecord {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(activeProfileId, "activeProfileId");
        Objects.requireNonNull(authoritativeNode, "authoritativeNode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }

    public Optional<String> optHandoffId() {
        return Optional.ofNullable(handoffId);
    }

    public Optional<ServerNodeId> optHandoffTargetNode() {
        return Optional.ofNullable(handoffTargetNode);
    }

    public Optional<Instant> optHandoffExpiresAt() {
        return Optional.ofNullable(handoffExpiresAt);
    }

    public boolean isLeaseExpired(Instant now) {
        return leaseExpiresAt.isBefore(now);
    }
}
