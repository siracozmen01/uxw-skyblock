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
}
