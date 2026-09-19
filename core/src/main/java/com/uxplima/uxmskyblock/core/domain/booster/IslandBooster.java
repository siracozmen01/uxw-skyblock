package com.uxplima.uxmskyblock.core.domain.booster;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record representing an applied island booster.
 *
 * @param id unique identifier of the booster instance
 * @param islandId target island ID
 * @param category target multiplier category
 * @param multiplier numerical multiplier value (e.g., 1.5, 2.0)
 * @param expiresAt scheduled expiration timestamp when active
 * @param createdAt creation timestamp
 * @param pausedAt timestamp when the booster was paused (null if active/ticking)
 * @param remainingSeconds frozen remaining seconds when paused
 */
public record IslandBooster(
        UUID id,
        IslandId islandId,
        BoosterCategory category,
        double multiplier,
        Instant expiresAt,
        Instant createdAt,
        @Nullable Instant pausedAt,
        long remainingSeconds) {

    public IslandBooster {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (multiplier <= 0) {
            throw new IllegalArgumentException("multiplier must be positive");
        }
    }

    public static IslandBooster create(
            IslandId islandId, BoosterCategory category, double multiplier, Duration duration, Instant now) {
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(now, "now must not be null");
        UUID id = UUID.randomUUID();
        Instant expiresAt = now.plus(duration);
        return new IslandBooster(id, islandId, category, multiplier, expiresAt, now, null, 0);
    }

    public boolean isPaused() {
        return pausedAt != null;
    }

    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (isPaused()) {
            return remainingSeconds > 0;
        }
        return now.isBefore(expiresAt);
    }

    public long effectiveRemainingSeconds(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (isPaused()) {
            return Math.max(0, remainingSeconds);
        }
        return Math.max(0, Duration.between(now, expiresAt).toSeconds());
    }

    public Duration effectiveRemainingDuration(Instant now) {
        return Duration.ofSeconds(effectiveRemainingSeconds(now));
    }

    public IslandBooster withPause(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (isPaused()) {
            return this;
        }
        long rem = Math.max(0, Duration.between(now, expiresAt).toSeconds());
        return new IslandBooster(id, islandId, category, multiplier, expiresAt, createdAt, now, rem);
    }

    public IslandBooster withResume(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!isPaused()) {
            return this;
        }
        Instant newExpires = now.plusSeconds(remainingSeconds);
        return new IslandBooster(id, islandId, category, multiplier, newExpires, createdAt, null, 0);
    }

    public IslandBooster withExtendedDuration(Duration extra, Instant now, Duration maxDuration) {
        Objects.requireNonNull(extra, "extra must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(maxDuration, "maxDuration must not be null");

        long currentRem = effectiveRemainingSeconds(now);
        long newRem = Math.min(maxDuration.toSeconds(), currentRem + extra.toSeconds());

        if (isPaused()) {
            return new IslandBooster(id, islandId, category, multiplier, expiresAt, createdAt, pausedAt, newRem);
        }
        Instant newExpires = now.plusSeconds(newRem);
        return new IslandBooster(id, islandId, category, multiplier, newExpires, createdAt, null, 0);
    }

    public IslandBooster withRefreshedDuration(Duration fullDuration, Instant now) {
        Objects.requireNonNull(fullDuration, "fullDuration must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (isPaused()) {
            return new IslandBooster(
                    id, islandId, category, multiplier, expiresAt, createdAt, pausedAt, fullDuration.toSeconds());
        }
        Instant newExpires = now.plus(fullDuration);
        return new IslandBooster(id, islandId, category, multiplier, newExpires, createdAt, null, 0);
    }
}
