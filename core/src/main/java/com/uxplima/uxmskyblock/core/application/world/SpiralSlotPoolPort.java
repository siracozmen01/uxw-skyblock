package com.uxplima.uxmskyblock.core.application.world;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;

/**
 * Outbound port for persistent Archimedean / Ulam spiral slot recycling.
 *
 * <p>Enforces coordinate reuse across island deletion lifecycles to prevent
 * unbounded Anvil file sprawl and unbounded world size growth.
 */
public interface SpiralSlotPoolPort {

    /**
     * Atomically claims the lowest-numbered vacated slot in the given world.
     *
     * @param worldName target world identifier
     * @return optional claimed slot, or empty if no vacated slots exist
     */
    Optional<RecycledSlot> claimNextAvailableSlot(String worldName);

    /**
     * Releases an allocated slot back into the recycling pool.
     *
     * @param slotIndex sequence index of the slot
     * @param worldName world identifier
     * @param gridX world center X coordinate
     * @param gridZ world center Z coordinate
     */
    void releaseSlot(long slotIndex, String worldName, int gridX, int gridZ);

    /**
     * Records an initial allocation for a brand new slot.
     *
     * @param slotIndex sequence index of the slot
     * @param worldName world identifier
     * @param gridX world center X coordinate
     * @param gridZ world center Z coordinate
     */
    void recordAllocatedSlot(long slotIndex, String worldName, int gridX, int gridZ);

    /**
     * Returns the total count of currently vacated and available slots in the given world.
     *
     * @param worldName target world identifier
     * @return number of available reusable slots
     */
    long countAvailableSlots(String worldName);

    /**
     * Finds a slot by its monotonic slot index.
     *
     * @param slotIndex sequence index
     * @return optional slot record
     */
    Optional<RecycledSlot> findBySlotIndex(long slotIndex);
}
