package com.uxplima.uxmskyblock.core.application.freeze;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port for updating and reading an island aggregate's orthogonal state dimensions.
 */
public interface IslandAdminFreezePort {

    /**
     * Updates an island's administrative enforcement state and optional freeze rationale.
     *
     * @param islandId target island
     * @param state new administrative state
     * @param freezeReason rationale if frozen, or null if normal
     */
    void updateAdministrativeState(IslandId islandId, AdministrativeState state, @Nullable String freezeReason);

    /**
     * Updates an island's administrative enforcement state, optional freeze rationale, and stages an outbox event atomically.
     *
     * @param islandId target island
     * @param state new administrative state
     * @param freezeReason rationale if frozen, or null if normal
     * @param outboxEvent optional event to stage atomically in the same transaction
     */
    default void updateAdministrativeState(
            IslandId islandId,
            AdministrativeState state,
            @Nullable String freezeReason,
            @Nullable StagedOutboxEvent outboxEvent) {
        updateAdministrativeState(islandId, state, freezeReason);
    }

    /**
     * Updates an island's economic solvency state.
     *
     * @param islandId target island
     * @param state new economic state
     */
    void updateEconomicState(IslandId islandId, EconomicState state);

    /**
     * Updates an island's economic solvency state and stages an outbox event atomically.
     *
     * @param islandId target island
     * @param state new economic state
     * @param outboxEvent optional event to stage atomically in the same transaction
     */
    default void updateEconomicState(IslandId islandId, EconomicState state, @Nullable StagedOutboxEvent outboxEvent) {
        updateEconomicState(islandId, state);
    }

    /**
     * Updates an island's lifecycle disposal status.
     *
     * @param islandId target island
     * @param lifecycle new lifecycle status
     */
    void updateLifecycle(IslandId islandId, IslandLifecycle lifecycle);

    /**
     * Retrieves the administrative freeze snapshot for an island if present.
     *
     * @param islandId target island
     * @return optional containing the freeze record
     */
    Optional<IslandFreezeRecord> findFreezeRecord(IslandId islandId);
}
