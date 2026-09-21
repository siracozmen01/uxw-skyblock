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
            IslandId islandId, @Nullable IslandName name, @Nullable StagedOutboxEvent outboxEvent) {
        updateCustomName(islandId, name);
    }

    /**
     * Takes {@code name} for {@code island} only if no other active island already holds it.
     *
     * <p>Reading the name, finding it free and then writing it leaves a window: two islands that read
     * at the same moment both find it free and both write, and the server then has two islands with
     * one name. The check and the write have to be one step, so this is a compare and set rather than
     * a setter, and the caller learns from the return value whether the name was taken.
     *
     * <p>Comparison follows {@link #findIslandIdByName(String)}: case insensitive, active islands
     * only. Renaming an island to the name it already holds succeeds and changes nothing but the
     * letter case.
     *
     * @param islandId target island id
     * @param name the name to take
     * @param outboxEvent optional event staged in the same transaction, only when the name is taken
     * @return true when the island now holds the name, false when another island already had it
     */
    boolean claimCustomName(IslandId islandId, IslandName name, @Nullable StagedOutboxEvent outboxEvent);

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
