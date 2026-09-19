package com.uxplima.uxmskyblock.core.application.recycle;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Outbound port for generating pre-deletion disaster-recovery backup snapshots.
 */
public interface IslandBackupPort {

    /**
     * Serializes island metadata, boundaries, and structure blocks into a disaster-recovery
     * schematic/NBT snapshot at {@code backups/islands/<island_id>_<timestamp>.schem}.
     *
     * @param island island domain aggregate
     * @param location island center location and world
     */
    void createPreDeletionBackup(Island island, IslandLocation location);
}
