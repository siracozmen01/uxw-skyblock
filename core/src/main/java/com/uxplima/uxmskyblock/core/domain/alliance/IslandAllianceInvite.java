package com.uxplima.uxmskyblock.core.domain.alliance;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain entity representing a pending bilateral alliance invite sent from one island to another.
 *
 * @param id the unique invite identifier
 * @param senderIslandId the island issuing the alliance invite
 * @param targetIslandId the target island receiving the alliance invite
 * @param senderProfileId the player profile initiating the invite
 * @param createdAt the creation timestamp
 * @param expiresAt the expiration timestamp
 */
public record IslandAllianceInvite(
        AllianceInviteId id,
        IslandId senderIslandId,
        IslandId targetIslandId,
        ProfileId senderProfileId,
        Instant createdAt,
        Instant expiresAt) {

    public IslandAllianceInvite {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(senderIslandId, "senderIslandId must not be null");
        Objects.requireNonNull(targetIslandId, "targetIslandId must not be null");
        Objects.requireNonNull(senderProfileId, "senderProfileId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (senderIslandId.equals(targetIslandId)) {
            throw new IllegalArgumentException("An island cannot invite itself to an alliance: " + senderIslandId);
        }
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.isAfter(expiresAt);
    }
}
