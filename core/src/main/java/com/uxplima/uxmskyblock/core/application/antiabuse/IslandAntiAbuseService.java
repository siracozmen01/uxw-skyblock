package com.uxplima.uxmskyblock.core.application.antiabuse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.antiabuse.CoopJoinCheckResult;
import com.uxplima.uxmskyblock.core.domain.antiabuse.IslandQuarantineRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.PlayerAntiAbuseRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Enterprise domain application service providing anti-alt and starter economy protection:
 * - Hard reset inventory/enderchest/equipment purge enforcement
 * - Island creation quarantine transfer window (item drop & visitor access block)
 * - Strict reset cooldowns and rolling daily reset quotas
 * - Co-op hopping locks preventing nomadic raiding of island banks/vaults
 */
public final class IslandAntiAbuseService {

    public static final Duration DEFAULT_QUARANTINE_DURATION = Duration.ofMinutes(15);
    public static final Duration DEFAULT_RESET_COOLDOWN = Duration.ofHours(12);
    public static final int DEFAULT_MAX_RESETS_PER_DAY = 3;
    public static final Duration DEFAULT_RESET_WINDOW_DURATION = Duration.ofHours(24);
    public static final Duration DEFAULT_COOP_JOIN_COOLDOWN = Duration.ofHours(24);

    /** How long this node trusts its own answer that an island is not quarantined. */
    public static final Duration DEFAULT_QUARANTINE_LOOKUP_TTL = Duration.ofSeconds(30);

    private final AntiAbuseStoragePort storagePort;
    private final boolean purgeInventoryOnReset;
    private final Duration quarantineDuration;
    private final Duration resetCooldown;
    private final int maxResetsPerDay;
    private final Duration resetWindowDuration;
    private final Duration coopJoinCooldown;
    private final Duration quarantineLookupTtl;
    private final Clock clock;

    private final Map<PlayerUuid, PlayerAntiAbuseRecord> playerRecords = new ConcurrentHashMap<>();
    private final Map<IslandId, IslandQuarantineRecord> activeQuarantines = new ConcurrentHashMap<>();

    /**
     * Islands this node has looked up and found clean, and when it last looked.
     *
     * <p>Every movement packet asked whether the island was quarantined, and an island with no
     * quarantine row is not in {@link #activeQuarantines}, so every one of those asks went to the
     * database. The common case was the uncached case: a player walking on a healthy island ran a
     * query per step.
     *
     * <p>This node already knows every quarantine it set itself, and loads the active ones at
     * startup. The lookup is only there to notice one another node set, so a bounded staleness is
     * the right price and a query per step is not.
     */
    private final Map<IslandId, Instant> knownClean = new ConcurrentHashMap<>();

    public IslandAntiAbuseService(
            AntiAbuseStoragePort storagePort,
            boolean purgeInventoryOnReset,
            Duration quarantineDuration,
            Duration resetCooldown,
            int maxResetsPerDay,
            Duration resetWindowDuration,
            Duration coopJoinCooldown,
            Duration quarantineLookupTtl,
            Clock clock) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.purgeInventoryOnReset = purgeInventoryOnReset;
        this.quarantineDuration = Objects.requireNonNull(quarantineDuration, "quarantineDuration must not be null");
        this.resetCooldown = Objects.requireNonNull(resetCooldown, "resetCooldown must not be null");
        this.maxResetsPerDay = maxResetsPerDay;
        this.resetWindowDuration = Objects.requireNonNull(resetWindowDuration, "resetWindowDuration must not be null");
        this.coopJoinCooldown = Objects.requireNonNull(coopJoinCooldown, "coopJoinCooldown must not be null");
        this.quarantineLookupTtl = Objects.requireNonNull(quarantineLookupTtl, "quarantineLookupTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");

        // Warm up active quarantines on startup
        try {
            activeQuarantines.putAll(storagePort.loadActiveQuarantines(clock.instant()));
        } catch (Exception ignored) {
            // Ignore during early bootstrap or tests with mock storage
        }
    }

    public IslandAntiAbuseService(AntiAbuseStoragePort storagePort) {
        this(
                storagePort,
                true,
                DEFAULT_QUARANTINE_DURATION,
                DEFAULT_RESET_COOLDOWN,
                DEFAULT_MAX_RESETS_PER_DAY,
                DEFAULT_RESET_WINDOW_DURATION,
                DEFAULT_COOP_JOIN_COOLDOWN,
                DEFAULT_QUARANTINE_LOOKUP_TTL,
                Clock.systemUTC());
    }

    public boolean purgeInventoryOnReset() {
        return purgeInventoryOnReset;
    }

    public Duration quarantineDuration() {
        return quarantineDuration;
    }

    public Duration resetCooldown() {
        return resetCooldown;
    }

    public int maxResetsPerDay() {
        return maxResetsPerDay;
    }

    public Duration resetWindowDuration() {
        return resetWindowDuration;
    }

    public Duration coopJoinCooldown() {
        return coopJoinCooldown;
    }

    public Clock clock() {
        return clock;
    }

    /**
     * Checks if a player is permitted to reset or delete their island.
     *
     * @param playerUuid target player UUID
     * @param bypass true if the player has bypass permission
     * @return outcome record
     */
    public ResetCheckResult checkResetAllowed(PlayerUuid playerUuid, boolean bypass) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        if (bypass) {
            return new ResetCheckResult.Bypassed();
        }

        Instant now = clock.instant();
        PlayerAntiAbuseRecord record = getOrLoadRecord(playerUuid);

        if (record.isResetCooldownActive(now, resetCooldown)) {
            Instant availableAt =
                    record.lastResetAt() != null ? record.lastResetAt().plus(resetCooldown) : now;
            Duration remaining = record.getResetCooldownRemaining(now, resetCooldown);
            return new ResetCheckResult.CooldownActive(availableAt, remaining);
        }

        if (record.isDailyLimitReached(now, maxResetsPerDay, resetWindowDuration)) {
            Instant nextWindow = record.getNextWindowStart(now, resetWindowDuration);
            Duration remaining = Duration.between(now, nextWindow);
            return new ResetCheckResult.DailyLimitExceeded(maxResetsPerDay, nextWindow, remaining);
        }

        int remainingResets = record.getRemainingResets(now, maxResetsPerDay, resetWindowDuration);
        return new ResetCheckResult.Allowed(remainingResets);
    }

    /**
     * Records an executed island reset, advancing the cooldown and daily reset counter.
     *
     * @param playerUuid target player UUID
     * @param now timestamp of the reset
     * @return updated record
     */
    public PlayerAntiAbuseRecord recordReset(PlayerUuid playerUuid, Instant now) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(now, "now must not be null");

        PlayerAntiAbuseRecord existing = getOrLoadRecord(playerUuid);
        PlayerAntiAbuseRecord updated = existing.withResetRecorded(now, resetWindowDuration);
        playerRecords.put(playerUuid, updated);
        storagePort.saveRecord(updated);
        return updated;
    }

    /**
     * Checks if a player is permitted to join an island co-op.
     *
     * @param playerUuid target player UUID
     * @param bypass true if the player has bypass permission
     * @return outcome record
     */
    public CoopJoinCheckResult checkCoopJoinAllowed(PlayerUuid playerUuid, boolean bypass) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        if (bypass) {
            return new CoopJoinCheckResult.Bypassed();
        }

        Instant now = clock.instant();
        PlayerAntiAbuseRecord record = getOrLoadRecord(playerUuid);

        if (record.isCoopCooldownActive(now)) {
            Instant availableAt = record.coopCooldownExpiresAt() != null ? record.coopCooldownExpiresAt() : now;
            Duration remaining = record.getCoopCooldownRemaining(now);
            return new CoopJoinCheckResult.CooldownActive(availableAt, remaining);
        }

        return new CoopJoinCheckResult.Allowed();
    }

    /**
     * Records a co-op team departure or kick, initiating the co-op hopping lock.
     *
     * @param playerUuid departing player UUID
     * @param now timestamp of departure
     * @return updated record
     */
    public PlayerAntiAbuseRecord recordCoopDeparture(PlayerUuid playerUuid, Instant now) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(now, "now must not be null");

        PlayerAntiAbuseRecord existing = getOrLoadRecord(playerUuid);
        Instant expiresAt = now.plus(coopJoinCooldown);
        PlayerAntiAbuseRecord updated = existing.withCoopCooldown(expiresAt);
        playerRecords.put(playerUuid, updated);
        storagePort.saveRecord(updated);
        return updated;
    }

    /**
     * Enters a newly created island into starter quarantine protection.
     *
     * @param islandId newly created island ID
     * @param now creation timestamp
     */
    public void quarantineNewIsland(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Instant expiresAt = now.plus(quarantineDuration);
        IslandQuarantineRecord record = new IslandQuarantineRecord(islandId, expiresAt, "NEW_ISLAND_CREATION");
        activeQuarantines.put(islandId, record);
        knownClean.remove(islandId);
        storagePort.saveQuarantine(record);
    }

    /**
     * Checks if an island is currently subject to starter quarantine restrictions.
     *
     * @param islandId target island ID
     * @param now reference timestamp
     * @return true if quarantine is active
     */
    public boolean isIslandQuarantined(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        IslandQuarantineRecord record = activeQuarantines.get(islandId);
        if (record == null) {
            if (recentlyFoundClean(islandId, now)) {
                return false;
            }
            Optional<IslandQuarantineRecord> opt = storagePort.findQuarantine(islandId);
            if (opt.isPresent()) {
                record = opt.get();
                activeQuarantines.put(islandId, record);
                knownClean.remove(islandId);
            } else {
                knownClean.put(islandId, now);
            }
        }

        if (record != null) {
            if (record.isQuarantined(now)) {
                return true;
            } else {
                activeQuarantines.remove(islandId);
                knownClean.put(islandId, now);
                storagePort.deleteQuarantine(islandId);
                return false;
            }
        }
        return false;
    }

    /** Whether this node looked recently enough to trust that the island has no quarantine. */
    private boolean recentlyFoundClean(IslandId islandId, Instant now) {
        Instant lookedAt = knownClean.get(islandId);
        if (lookedAt == null) {
            return false;
        }
        if (lookedAt.plus(quarantineLookupTtl).isBefore(now)) {
            knownClean.remove(islandId);
            return false;
        }
        return true;
    }

    /**
     * Returns the remaining quarantine duration for an island, or empty if not quarantined.
     *
     * @param islandId target island ID
     * @param now reference timestamp
     * @return remaining duration if active
     */
    public Optional<Duration> getQuarantineRemaining(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (!isIslandQuarantined(islandId, now)) {
            return Optional.empty();
        }
        IslandQuarantineRecord record = activeQuarantines.get(islandId);
        return record != null ? Optional.of(record.getRemaining(now)) : Optional.empty();
    }

    /**
     * Administratively terminates quarantine protection for an island early.
     *
     * @param islandId target island ID
     */
    public void liftQuarantine(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        activeQuarantines.remove(islandId);
        knownClean.put(islandId, clock.instant());
        storagePort.deleteQuarantine(islandId);
    }

    private PlayerAntiAbuseRecord getOrLoadRecord(PlayerUuid playerUuid) {
        return playerRecords.computeIfAbsent(
                playerUuid, uuid -> storagePort.findRecord(uuid).orElseGet(() -> PlayerAntiAbuseRecord.initial(uuid)));
    }
}
