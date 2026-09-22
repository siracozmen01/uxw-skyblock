package com.uxplima.uxmskyblock.core.application.freeze;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

/**
 * Core application service orchestrating the 4-Dimensional Orthogonal State Architecture,
 * administrative quarantine lockdown, visitor eviction, and state transition invariants.
 */
public final class IslandAdminFreezeService {

    private final IslandStoragePort islandStoragePort;
    private final IslandMutationLock mutationLock;
    private final IslandAdminFreezePort freezeStoragePort;
    private final @Nullable IslandVisitorEvictionPort visitorEvictionPort;
    private final @Nullable OutboxPort outboxPort;

    /** How long a node may answer "is this island frozen" from memory before reading it again. */
    public static final java.time.Duration DEFAULT_FREEZE_LOOKUP_TTL = java.time.Duration.ofSeconds(30);

    private final java.time.Duration freezeLookupTtl;

    /**
     * Whether each island is frozen, and when that was read.
     *
     * <p>Every block a player breaks or places goes through this question first, and it read the
     * freeze table every single time, on the thread the event arrived on. An island with no freeze
     * record then ran a second query for the island itself, so building on a healthy island, which
     * is what every player does all day, cost two queries per block.
     *
     * <p>A node writes its own freezes and unfreezes here as it makes them, so nothing an
     * administrator does on this server waits. The window is only how long it may take to notice a
     * freeze another node applied.
     */
    private final java.util.concurrent.ConcurrentMap<IslandId, Boolean> frozen =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentMap<IslandId, java.time.Instant> readAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.time.Clock clock;

    public IslandAdminFreezeService(
            IslandStoragePort islandStoragePort,
            IslandMutationLock mutationLock,
            IslandAdminFreezePort freezeStoragePort,
            @Nullable IslandVisitorEvictionPort visitorEvictionPort,
            @Nullable OutboxPort outboxPort) {
        this(
                islandStoragePort,
                mutationLock,
                freezeStoragePort,
                visitorEvictionPort,
                outboxPort,
                DEFAULT_FREEZE_LOOKUP_TTL,
                java.time.Clock.systemUTC());
    }

    /** The canonical constructor, carrying how long an answer may be given from memory. */
    public IslandAdminFreezeService(
            IslandStoragePort islandStoragePort,
            IslandMutationLock mutationLock,
            IslandAdminFreezePort freezeStoragePort,
            @Nullable IslandVisitorEvictionPort visitorEvictionPort,
            @Nullable OutboxPort outboxPort,
            java.time.Duration freezeLookupTtl,
            java.time.Clock clock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock must not be null");
        this.freezeStoragePort = Objects.requireNonNull(freezeStoragePort, "freezeStoragePort must not be null");
        this.visitorEvictionPort = visitorEvictionPort;
        this.outboxPort = outboxPort;
        this.freezeLookupTtl = Objects.requireNonNull(freezeLookupTtl, "freezeLookupTtl must not be null");
        if (freezeLookupTtl.isNegative()) {
            throw new IllegalArgumentException("freezeLookupTtl must not be negative: " + freezeLookupTtl);
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IslandAdminFreezeService(
            IslandStoragePort islandStoragePort,
            IslandAdminFreezePort freezeStoragePort,
            @Nullable IslandVisitorEvictionPort visitorEvictionPort,
            @Nullable OutboxPort outboxPort) {
        this(islandStoragePort, new IslandMutationLock(), freezeStoragePort, visitorEvictionPort, outboxPort);
    }

    public boolean freezeIsland(IslandId islandId, String reason, String actor) {
        // Read, change and write is one thing on one island. Two of these running at once
        // on the same island both read it as it was and the second write erases the first.
        return mutationLock.inside(islandId, () -> freezeIslandInside(islandId, reason, actor));
    }

    private boolean freezeIslandInside(IslandId islandId, String reason, String actor) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        if (!island.lifecycle().isOperational()) {
            throw new IllegalStateException("Cannot freeze island while lifecycle is " + island.lifecycle());
        }

        Island frozenIsland = island.freeze(reason);
        StagedOutboxEvent freezeOutboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_FROZEN",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"reason\":\"%s\",\"actor\":\"%s\",\"timestamp\":\"%s\"}",
                                islandId.value(), reason, actor, Instant.now()))
                : null;
        freezeStoragePort.updateAdministrativeState(islandId, AdministrativeState.FROZEN, reason, freezeOutboxEvent);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(frozenIsland, location);
        }

        if (visitorEvictionPort != null) {
            visitorEvictionPort.evictNonStaffVisitors(islandId, reason);
        }

        remember(islandId, true);
        return true;
    }

    public boolean unfreezeIsland(IslandId islandId, String actor) {
        // Read, change and write is one thing on one island. Two of these running at once
        // on the same island both read it as it was and the second write erases the first.
        return mutationLock.inside(islandId, () -> unfreezeIslandInside(islandId, actor));
    }

    private boolean unfreezeIslandInside(IslandId islandId, String actor) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        if (!island.lifecycle().isOperational()) {
            throw new IllegalStateException("Cannot unfreeze island while lifecycle is " + island.lifecycle());
        }

        Island unfrozenIsland = island.unfreeze();
        StagedOutboxEvent unfreezeOutboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_UNFROZEN",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"actor\":\"%s\",\"timestamp\":\"%s\"}",
                                islandId.value(), actor, Instant.now()))
                : null;
        freezeStoragePort.updateAdministrativeState(islandId, AdministrativeState.NORMAL, null, unfreezeOutboxEvent);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(unfrozenIsland, location);
        }

        remember(islandId, false);
        return true;
    }

    /**
     * Whether an administrator has frozen this island.
     *
     * <p>Answered from memory when it was read recently enough, because the protection path asks it
     * for every block anybody touches.
     *
     * <p>It used to fall back to reading the island itself when there was no freeze record, which is
     * a second query for an answer the one caller already holds: the protection listener has the
     * island in its hand and reads {@code island.isFrozen()} on the same line. The island's own
     * administrative state stays the island's to report.
     */
    public boolean isFrozen(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        java.time.Instant now = clock.instant();
        java.time.Instant lastRead = readAt.get(islandId);
        Boolean cached = frozen.get(islandId);
        if (cached != null && lastRead != null && lastRead.plus(freezeLookupTtl).isAfter(now)) {
            return cached;
        }
        boolean fresh = freezeStoragePort
                .findFreezeRecord(islandId)
                .map(IslandFreezeRecord::isFrozen)
                .orElse(false);
        frozen.put(islandId, fresh);
        readAt.put(islandId, now);
        return fresh;
    }

    /** Remembers what this node just did, so nothing it did waits for the window. */
    private void remember(IslandId islandId, boolean isFrozen) {
        frozen.put(islandId, isFrozen);
        readAt.put(islandId, clock.instant());
    }

    /**
     * Lets go of what is remembered about an island.
     *
     * <p>An island id is a fresh uuid every time, so holding an erased one never gives a wrong
     * answer. It simply never lets go.
     */
    public void forgetIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        frozen.remove(islandId);
        readAt.remove(islandId);
    }

    /** How many islands this node is holding an answer for, for a caller that wants to say so. */
    public int islandsHeldInMemory() {
        return frozen.size();
    }

    public Optional<IslandFreezeRecord> getFreezeRecord(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return freezeStoragePort.findFreezeRecord(islandId);
    }

    public void transitionEconomicState(IslandId islandId, EconomicState targetState) {
        // Read, change and write is one thing on one island. Two of these running at once
        // on the same island both read it as it was and the second write erases the first.
        mutationLock.inside(islandId, () -> transitionEconomicStateInside(islandId, targetState));
    }

    private void transitionEconomicStateInside(IslandId islandId, EconomicState targetState) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(targetState, "targetState must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        if (!island.lifecycle().isOperational()) {
            throw new IllegalStateException("Cannot mutate economic state while lifecycle is " + island.lifecycle());
        }

        if (!island.economicState().canTransitionTo(targetState)) {
            throw new IllegalStateException(
                    "Invalid economic state transition from " + island.economicState() + " to " + targetState);
        }

        Island updated = island.withEconomicState(targetState);
        StagedOutboxEvent economicOutboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_ECONOMIC_STATE_CHANGED",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"previousState\":\"%s\",\"newState\":\"%s\",\"timestamp\":\"%s\"}",
                                islandId.value(), island.economicState(), targetState, Instant.now()))
                : null;
        freezeStoragePort.updateEconomicState(islandId, targetState, economicOutboxEvent);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(updated, location);
        }
    }

    public void transitionLifecycle(IslandId islandId, IslandLifecycle targetLifecycle) {
        // Read, change and write is one thing on one island. Two of these running at once
        // on the same island both read it as it was and the second write erases the first.
        mutationLock.inside(islandId, () -> transitionLifecycleInside(islandId, targetLifecycle));
    }

    private void transitionLifecycleInside(IslandId islandId, IslandLifecycle targetLifecycle) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(targetLifecycle, "targetLifecycle must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        Island updated = island.withLifecycle(targetLifecycle);
        freezeStoragePort.updateLifecycle(islandId, targetLifecycle);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(updated, location);
        }
    }

    public Optional<Island> findIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findIslandById(islandId);
    }

    public Optional<IslandLocation> findLocation(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findLocationByIslandId(islandId);
    }
}
