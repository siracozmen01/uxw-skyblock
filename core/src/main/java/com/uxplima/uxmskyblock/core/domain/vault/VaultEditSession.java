package com.uxplima.uxmskyblock.core.domain.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Represents an exclusive pessimistic lease acquired on an island vault page.
 *
 * @param sessionId unique lease session identifier
 * @param islandId target island ID
 * @param page 1-based page number
 * @param playerUuid editing player UUID
 * @param leaseEpoch fencing epoch when lease was assigned
 * @param basePageVersion expected page OCC version at start of edit
 * @param state lifecycle state
 * @param escrowJournal optional serialized JSON escrow journal
 * @param openedAt creation timestamp
 * @param expiresAt lease expiration timestamp
 * @param closedAt closure timestamp or null
 */
public record VaultEditSession(
        VaultSessionId sessionId,
        IslandId islandId,
        int page,
        UUID playerUuid,
        long leaseEpoch,
        long basePageVersion,
        VaultSessionState state,
        @Nullable String escrowJournal,
        Instant openedAt,
        Instant expiresAt,
        @Nullable Instant closedAt) {

    public VaultEditSession {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1: " + page);
        }
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        if (leaseEpoch < 1) {
            throw new IllegalArgumentException("leaseEpoch must be >= 1: " + leaseEpoch);
        }
        if (basePageVersion < 1) {
            throw new IllegalArgumentException("basePageVersion must be >= 1: " + basePageVersion);
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
