package com.uxplima.uxmskyblock.core.domain.inactivity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Audit record detailing an action performed by the inactivity evaluation engine on an island.
 */
public record IslandSuccessionRecord(
        IslandId islandId,
        SuccessionOutcome outcome,
        @Nullable ProfileId formerOwnerProfileId,
        @Nullable ProfileId newOwnerProfileId,
        Instant executedAt,
        String summary) {

    public IslandSuccessionRecord {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(executedAt, "executedAt must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
    }

    public Optional<ProfileId> optFormerOwnerProfileId() {
        return Optional.ofNullable(formerOwnerProfileId);
    }

    public Optional<ProfileId> optNewOwnerProfileId() {
        return Optional.ofNullable(newOwnerProfileId);
    }
}
