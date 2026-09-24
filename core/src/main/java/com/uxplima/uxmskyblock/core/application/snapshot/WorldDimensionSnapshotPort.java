package com.uxplima.uxmskyblock.core.application.snapshot;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;

/**
 * Outbound driven port for world dimension chunk extraction and Folia thread coordination (Section 2.29).
 *
 * <p>Implemented strictly by :bukkit-adapter.
 */
public interface WorldDimensionSnapshotPort {

    /**
     * Captures chunk geometry and tile entities for the target root and dimension.
     *
     * @param rootRef target root reference
     * @param dimensionId dimension identifier
     * @return serialized dimension payload
     */
    byte[] captureWorldDimension(PrimaryGameplayRootRef rootRef, DimensionId dimensionId);

    /**
     * Restores world dimension state for the target root and dimension.
     *
     * @param rootRef target root reference
     * @param dimensionId dimension identifier
     * @param dimensionPayload serialized dimension payload
     */
    void restoreWorldDimension(PrimaryGameplayRootRef rootRef, DimensionId dimensionId, byte[] dimensionPayload);

    /**
     * Brings back the non-economic entities a dimension payload holds, once its blocks are back.
     *
     * <p>Each entity comes back once however often this runs: one still alive, or one an earlier run
     * already brought back, is not brought back again, so a restore unit replayed after a stop leaves
     * the same creatures as one that ran once. A payload that holds no entities brings back none.
     *
     * @param rootRef target root reference
     * @param dimensionId dimension identifier
     * @param dimensionPayload serialized dimension payload
     */
    default void restoreEntities(PrimaryGameplayRootRef rootRef, DimensionId dimensionId, byte[] dimensionPayload) {}
}
