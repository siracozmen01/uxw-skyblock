package com.uxplima.uxmskyblock.core.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of a batch worker claiming operation on the transactional outbox.
 */
public record OutboxClaim(
        String workerId, String claimToken, Instant claimExpiresAt, List<OutboxEventRecord> claimedEvents) {

    public OutboxClaim {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        claimedEvents = (claimedEvents == null) ? List.of() : List.copyOf(claimedEvents);
    }

    public static OutboxClaim empty(String workerId, String claimToken, Instant claimExpiresAt) {
        return new OutboxClaim(workerId, claimToken, claimExpiresAt, List.of());
    }
}
