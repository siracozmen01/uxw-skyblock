package com.uxplima.uxmskyblock.core.application.freeze;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandEconomicStateChangedEvent;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFrozenEvent;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandUnfrozenEvent;
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
    private final IslandAdminFreezePort freezeStoragePort;
    private final @Nullable IslandVisitorEvictionPort visitorEvictionPort;
    private final @Nullable OutboxPort outboxPort;

    public IslandAdminFreezeService(
            IslandStoragePort islandStoragePort,
            IslandAdminFreezePort freezeStoragePort,
            @Nullable IslandVisitorEvictionPort visitorEvictionPort,
            @Nullable OutboxPort outboxPort) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.freezeStoragePort = Objects.requireNonNull(freezeStoragePort, "freezeStoragePort must not be null");
        this.visitorEvictionPort = visitorEvictionPort;
        this.outboxPort = outboxPort;
    }

    public boolean freezeIsland(IslandId islandId, String reason, String actor) {
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
        freezeStoragePort.updateAdministrativeState(islandId, AdministrativeState.FROZEN, reason);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(frozenIsland, location);
        }

        if (visitorEvictionPort != null) {
            visitorEvictionPort.evictNonStaffVisitors(islandId, reason);
        }

        if (outboxPort != null) {
            IslandFrozenEvent event = IslandFrozenEvent.create(islandId, reason, actor, Instant.now());
            outboxPort.stageEvent(
                    event.eventId(),
                    "ISLAND_FROZEN",
                    islandId.value().toString(),
                    String.format(
                            "{\"islandId\":\"%s\",\"reason\":\"%s\",\"actor\":\"%s\",\"timestamp\":\"%s\"}",
                            islandId.value(), reason, actor, event.timestamp()));
        }

        return true;
    }

    public boolean unfreezeIsland(IslandId islandId, String actor) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        if (!island.lifecycle().isOperational()) {
            throw new IllegalStateException("Cannot unfreeze island while lifecycle is " + island.lifecycle());
        }

        Island unfrozenIsland = island.unfreeze();
        freezeStoragePort.updateAdministrativeState(islandId, AdministrativeState.NORMAL, null);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(unfrozenIsland, location);
        }

        if (outboxPort != null) {
            IslandUnfrozenEvent event = IslandUnfrozenEvent.create(islandId, actor, Instant.now());
            outboxPort.stageEvent(
                    event.eventId(),
                    "ISLAND_UNFROZEN",
                    islandId.value().toString(),
                    String.format(
                            "{\"islandId\":\"%s\",\"actor\":\"%s\",\"timestamp\":\"%s\"}",
                            islandId.value(), actor, event.timestamp()));
        }

        return true;
    }

    public boolean isFrozen(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return freezeStoragePort
                .findFreezeRecord(islandId)
                .map(IslandFreezeRecord::isFrozen)
                .orElseGet(() -> islandStoragePort
                        .findIslandById(islandId)
                        .map(Island::isFrozen)
                        .orElse(false));
    }

    public Optional<IslandFreezeRecord> getFreezeRecord(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return freezeStoragePort.findFreezeRecord(islandId);
    }

    public void transitionEconomicState(IslandId islandId, EconomicState targetState) {
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
        freezeStoragePort.updateEconomicState(islandId, targetState);

        IslandLocation location =
                islandStoragePort.findLocationByIslandId(islandId).orElse(null);
        if (location != null) {
            islandStoragePort.saveIsland(updated, location);
        }

        if (outboxPort != null) {
            IslandEconomicStateChangedEvent event = IslandEconomicStateChangedEvent.create(
                    islandId, island.economicState(), targetState, Instant.now());
            outboxPort.stageEvent(
                    event.eventId(),
                    "ISLAND_ECONOMIC_STATE_CHANGED",
                    islandId.value().toString(),
                    String.format(
                            "{\"islandId\":\"%s\",\"previousState\":\"%s\",\"newState\":\"%s\",\"timestamp\":\"%s\"}",
                            islandId.value(), island.economicState(), targetState, event.timestamp()));
        }
    }

    public void transitionLifecycle(IslandId islandId, IslandLifecycle targetLifecycle) {
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
