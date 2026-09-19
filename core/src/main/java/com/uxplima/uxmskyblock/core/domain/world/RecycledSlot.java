package com.uxplima.uxmskyblock.core.domain.world;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record representing a discrete world grid slot in the spiral pool,
 * tracking whether it is actively allocated or vacated and eligible for recycling.
 *
 * @param slotIndex unique monotonic slot index
 * @param worldName world identifier
 * @param gridX world center X coordinate
 * @param gridZ world center Z coordinate
 * @param isAllocated true if currently in use by an island, false if available
 * @param vacatedAt timestamp when this slot was returned to the pool, or null if allocated
 */
public record RecycledSlot(
        long slotIndex,
        String worldName,
        int gridX,
        int gridZ,
        boolean isAllocated,
        @Nullable Instant vacatedAt) {

    public RecycledSlot {
        Objects.requireNonNull(worldName, "worldName must not be null");
    }

    public static RecycledSlot allocated(long slotIndex, String worldName, int gridX, int gridZ) {
        return new RecycledSlot(slotIndex, worldName, gridX, gridZ, true, null);
    }

    public static RecycledSlot vacated(long slotIndex, String worldName, int gridX, int gridZ, Instant vacatedAt) {
        return new RecycledSlot(
                slotIndex,
                worldName,
                gridX,
                gridZ,
                false,
                Objects.requireNonNull(vacatedAt, "vacatedAt must not be null"));
    }
}
