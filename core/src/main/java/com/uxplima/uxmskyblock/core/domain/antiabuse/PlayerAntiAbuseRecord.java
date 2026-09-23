package com.uxplima.uxmskyblock.core.domain.antiabuse;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record tracking a player's anti-abuse cooldowns and reset history.
 *
 * @param playerUuid target player UUID
 * @param lastResetAt timestamp of the last island reset/deletion, or null if none
 * @param resetsTodayCount number of resets consumed in current rolling window
 * @param resetWindowStart start timestamp of the current daily reset window, or null
 * @param coopCooldownExpiresAt timestamp when co-op hopping quarantine expires, or null
 * @param inventoryPurgeOwed whether a reset emptied the island while the player was away, so their
 *     inventory is still to be emptied when they next play
 */
public record PlayerAntiAbuseRecord(
        PlayerUuid playerUuid,
        @Nullable Instant lastResetAt,
        int resetsTodayCount,
        @Nullable Instant resetWindowStart,
        @Nullable Instant coopCooldownExpiresAt,
        boolean inventoryPurgeOwed) {

    public PlayerAntiAbuseRecord {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
    }

    /** A record that owes no inventory purge. */
    public PlayerAntiAbuseRecord(
            PlayerUuid playerUuid,
            @Nullable Instant lastResetAt,
            int resetsTodayCount,
            @Nullable Instant resetWindowStart,
            @Nullable Instant coopCooldownExpiresAt) {
        this(playerUuid, lastResetAt, resetsTodayCount, resetWindowStart, coopCooldownExpiresAt, false);
    }

    /** This record, owing an inventory purge or not. */
    public PlayerAntiAbuseRecord withInventoryPurgeOwed(boolean owed) {
        return new PlayerAntiAbuseRecord(
                playerUuid, lastResetAt, resetsTodayCount, resetWindowStart, coopCooldownExpiresAt, owed);
    }

    public static PlayerAntiAbuseRecord initial(PlayerUuid playerUuid) {
        return new PlayerAntiAbuseRecord(playerUuid, null, 0, null, null);
    }

    public boolean isResetCooldownActive(Instant now, Duration cooldown) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (lastResetAt == null) {
            return false;
        }
        return now.isBefore(lastResetAt.plus(cooldown));
    }

    public Duration getResetCooldownRemaining(Instant now, Duration cooldown) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (lastResetAt == null) {
            return Duration.ZERO;
        }
        Instant availableAt = lastResetAt.plus(cooldown);
        if (!now.isBefore(availableAt)) {
            return Duration.ZERO;
        }
        return Duration.between(now, availableAt);
    }

    public boolean isDailyLimitReached(Instant now, int maxResetsPerDay, Duration windowDuration) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(windowDuration, "windowDuration must not be null");
        if (maxResetsPerDay <= 0) {
            return false;
        }
        if (resetWindowStart == null || !now.isBefore(resetWindowStart.plus(windowDuration))) {
            return false;
        }
        return resetsTodayCount >= maxResetsPerDay;
    }

    public int getRemainingResets(Instant now, int maxResetsPerDay, Duration windowDuration) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(windowDuration, "windowDuration must not be null");
        if (maxResetsPerDay <= 0) {
            return -1;
        }
        if (resetWindowStart == null || !now.isBefore(resetWindowStart.plus(windowDuration))) {
            return maxResetsPerDay;
        }
        return Math.max(0, maxResetsPerDay - resetsTodayCount);
    }

    public Instant getNextWindowStart(Instant now, Duration windowDuration) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(windowDuration, "windowDuration must not be null");
        if (resetWindowStart == null || !now.isBefore(resetWindowStart.plus(windowDuration))) {
            return now;
        }
        return resetWindowStart.plus(windowDuration);
    }

    public PlayerAntiAbuseRecord withResetRecorded(Instant now, Duration windowDuration) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(windowDuration, "windowDuration must not be null");

        int newCount;
        Instant newWindowStart;
        if (resetWindowStart == null || !now.isBefore(resetWindowStart.plus(windowDuration))) {
            newWindowStart = now;
            newCount = 1;
        } else {
            newWindowStart = resetWindowStart;
            newCount = resetsTodayCount + 1;
        }

        return new PlayerAntiAbuseRecord(
                playerUuid, now, newCount, newWindowStart, coopCooldownExpiresAt, inventoryPurgeOwed);
    }

    public boolean isCoopCooldownActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return coopCooldownExpiresAt != null && now.isBefore(coopCooldownExpiresAt);
    }

    public Duration getCoopCooldownRemaining(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (coopCooldownExpiresAt == null || !now.isBefore(coopCooldownExpiresAt)) {
            return Duration.ZERO;
        }
        return Duration.between(now, coopCooldownExpiresAt);
    }

    public PlayerAntiAbuseRecord withCoopCooldown(Instant expiresAt) {
        return new PlayerAntiAbuseRecord(
                playerUuid, lastResetAt, resetsTodayCount, resetWindowStart, expiresAt, inventoryPurgeOwed);
    }

    public PlayerAntiAbuseRecord withoutCoopCooldown() {
        return new PlayerAntiAbuseRecord(
                playerUuid, lastResetAt, resetsTodayCount, resetWindowStart, null, inventoryPurgeOwed);
    }
}
