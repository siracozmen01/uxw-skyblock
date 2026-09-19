package com.uxplima.uxmskyblock.core.application.name;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port for custom island names (Section 2.41).
 */
public interface IslandNameStoragePort {

    /**
     * Updates or clears the custom name for an island.
     *
     * @param islandId target island id
     * @param name new island name, or null to reset to default
     */
    void updateCustomName(IslandId islandId, @Nullable IslandName name);

    /**
     * Updates or clears the custom name for an island and stages an outbox event atomically.
     *
     * @param islandId target island id
     * @param name new island name, or null to reset to default
     * @param outboxEvent optional event to stage atomically in the same transaction
     */
    default void updateCustomName(
            IslandId islandId,
            @Nullable IslandName name,
            @Nullable StagedOutboxEvent outboxEvent) {
        updateCustomName(islandId, name);
    }

    /**
     * Retrieves the custom name for an island if assigned.
     *
     * @param islandId target island id
     * @return optional containing the custom name if configured
     */
    Optional<IslandName> findCustomName(IslandId islandId);

    /**
     * Finds an island by its custom name (case-insensitive).
     *
     * @param name candidate name
     * @return optional containing matching island id
     */
    Optional<IslandId> findIslandIdByName(String name);
}
