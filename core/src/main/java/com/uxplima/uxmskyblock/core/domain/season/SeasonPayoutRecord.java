package com.uxplima.uxmskyblock.core.domain.season;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record representing an offline-durable competitive season reward payout.
 */
public record SeasonPayoutRecord(
        String payoutId,
        SeasonId seasonId,
        PlayerUuid recipient,
        String rewardAction,
        SeasonPayoutState state,
        Instant createdAt,
        @Nullable Instant dispatchedAt) {

    public SeasonPayoutRecord {
        Objects.requireNonNull(payoutId, "payoutId cannot be null");
        Objects.requireNonNull(seasonId, "seasonId cannot be null");
        Objects.requireNonNull(recipient, "recipient cannot be null");
        Objects.requireNonNull(rewardAction, "rewardAction cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }
}
