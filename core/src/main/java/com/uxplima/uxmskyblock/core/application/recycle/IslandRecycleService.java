package com.uxplima.uxmskyblock.core.application.recycle;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain application service orchestrating safe island deletion, multi-step
 * cryptographic confirmation challenges, pre-deletion disaster recovery backups,
 * Folia-native asynchronous chunk voiding, and Archimedean spiral slot recycling.
 */
public final class IslandRecycleService {

    public sealed interface RecycleResult {
        record Success(IslandId islandId, long slotIndex, String worldName, int gridX, int gridZ)
                implements RecycleResult {}

        record IslandNotFound(IslandId islandId) implements RecycleResult {}

        record NotOwner(IslandId islandId, ProfileId requester) implements RecycleResult {}

        record InvalidChallenge(String reason) implements RecycleResult {}

        record Failure(String reason) implements RecycleResult {}
    }

    private record ChallengeEntry(IslandId islandId, ResetChallenge challenge) {}

    public static final Duration DEFAULT_CHALLENGE_TTL = Duration.ofSeconds(60);

    private final IslandStoragePort islandStoragePort;
    private final WorldGridAllocationPort worldGridAllocationPort;
    private final SpiralSlotPoolPort spiralSlotPoolPort;
    private final @Nullable IslandVoidingPort voidingPort;
    private final @Nullable IslandBackupPort backupPort;
    private final @Nullable OutboxPort outboxPort;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<ProfileId, ChallengeEntry> pendingChallenges = new ConcurrentHashMap<>();

    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort,
            Clock clock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.worldGridAllocationPort =
                Objects.requireNonNull(worldGridAllocationPort, "worldGridAllocationPort must not be null");
        this.spiralSlotPoolPort = Objects.requireNonNull(spiralSlotPoolPort, "spiralSlotPoolPort must not be null");
        this.voidingPort = voidingPort;
        this.backupPort = backupPort;
        this.outboxPort = outboxPort;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secureRandom = new SecureRandom();
    }

    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort) {
        this(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                Clock.systemUTC());
    }

    /**
     * Issues a temporary 4-digit cryptographic verification challenge for island deletion/reset.
     *
     * @param requesterProfileId profile of player requesting reset
     * @param islandId target island ID
     * @return generated challenge with 60-second expiry
     */
    public ResetChallenge generateResetChallenge(ProfileId requesterProfileId, IslandId islandId) {
        Objects.requireNonNull(requesterProfileId, "requesterProfileId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");

        int randomDigits = secureRandom.nextInt(10000);
        String code = String.format("%04d", randomDigits);
        Instant expiresAt = clock.instant().plus(DEFAULT_CHALLENGE_TTL);
        ResetChallenge challenge = new ResetChallenge(code, expiresAt);

        pendingChallenges.put(requesterProfileId, new ChallengeEntry(islandId, challenge));
        return challenge;
    }

    /**
     * Verifies if a pending challenge matches and has not expired.
     *
     * @param requesterProfileId profile ID
     * @param code 4-digit code provided
     * @return true if valid
     */
    public boolean verifyResetChallenge(ProfileId requesterProfileId, String code) {
        if (code == null) {
            return false;
        }
        ChallengeEntry entry = pendingChallenges.get(requesterProfileId);
        if (entry == null) {
            return false;
        }
        if (entry.challenge().isExpired(clock.instant())) {
            pendingChallenges.remove(requesterProfileId);
            return false;
        }
        return entry.challenge().code().equals(code.trim());
    }

    /**
     * Clears any pending challenge for the specified requester.
     *
     * @param requesterProfileId profile ID
     */
    public void cancelResetChallenge(ProfileId requesterProfileId) {
        pendingChallenges.remove(requesterProfileId);
    }

    /**
     * Safely executes an island reset or deletion.
     *
     * @param requester profile ID of the initiator
     * @param islandId target island ID
     * @param verificationCode verification code string, or null if admin bypass
     * @param adminBypass true if initiated by staff bypass permission
     * @return outcome record
     */
    public RecycleResult executeReset(
            ProfileId requester,
            IslandId islandId,
            @Nullable String verificationCode,
            boolean adminBypass) {
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            return new RecycleResult.IslandNotFound(islandId);
        }
        Island island = optIsland.get();

        if (!adminBypass) {
            if (!island.ownerProfileId().equals(requester)) {
                return new RecycleResult.NotOwner(islandId, requester);
            }
            if (!verifyResetChallenge(requester, verificationCode)) {
                return new RecycleResult.InvalidChallenge(
                        verificationCode == null ? "Confirmation required." : "Invalid or expired confirmation code.");
            }
            pendingChallenges.remove(requester);
        }

        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        if (optLocation.isEmpty()) {
            return new RecycleResult.Failure("Island location record missing for islandId=" + islandId);
        }
        IslandLocation location = optLocation.get();

        // 1. Pre-deletion backup snapshot
        if (backupPort != null) {
            try {
                backupPort.createPreDeletionBackup(island, location);
            } catch (Exception e) {
                // Log and continue to prevent blocking emergency administrative deletions
            }
        }

        // 2. Resolve world grid allocation and return slot to SpiralSlotPool
        long slotIndex = 0L;
        String worldName = location.worldName();
        int gridX = location.bounds().centerX();
        int gridZ = location.bounds().centerZ();

        Optional<WorldGridAllocation> optAlloc = worldGridAllocationPort.findByIslandId(islandId);
        if (optAlloc.isPresent()) {
            WorldGridAllocation alloc = optAlloc.get();
            slotIndex = alloc.sequenceIndex();
            worldName = alloc.worldName();
            gridX = alloc.centerX();
            gridZ = alloc.centerZ();
        }

        spiralSlotPoolPort.releaseSlot(slotIndex, worldName, gridX, gridZ);

        // 3. Folia-native asynchronous chunk voiding
        if (voidingPort != null) {
            voidingPort.voidIslandChunks(islandId, worldName, island.bounds());
        }

        // 4. Staged outbox event emission and storage deletion
        String payload = String.format(
                "{\"islandId\":\"%s\",\"ownerProfileId\":\"%s\",\"slotIndex\":%d,\"worldName\":\"%s\",\"gridX\":%d,\"gridZ\":%d}",
                islandId.value(), island.ownerProfileId().value(), slotIndex, worldName, gridX, gridZ);

        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(EventId.random(), "ISLAND_RECYCLED", islandId.value().toString(), payload)
                : null;

        islandStoragePort.deleteIsland(islandId, outboxEvent);

        if (outboxPort != null && outboxEvent != null) {
            outboxPort.stageEvent(
                    outboxEvent.id(), outboxEvent.eventType(), outboxEvent.aggregateId(), outboxEvent.payload());
        }

        return new RecycleResult.Success(islandId, slotIndex, worldName, gridX, gridZ);
    }
}
