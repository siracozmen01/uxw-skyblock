package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * The creation action that makes an island an AcidIsland island: records it and fills the air around it
 * with the acid sea, on a floor.
 *
 * <p>The sea spans many chunks, and on Folia each chunk belongs to the region that owns it, so each
 * chunk's column of sea is laid on that region's thread. A block the island already has is kept.
 */
public final class AcidSeaStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:acid-sea";

    private static final Logger LOGGER = Logger.getLogger(AcidSeaStart.class.getName());

    private final AcidIslandService service;
    private final SchedulerPort scheduler;
    private final AcidIslandConfiguration.Sea sea;

    public AcidSeaStart(AcidIslandService service, SchedulerPort scheduler, AcidIslandConfiguration.Sea sea) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.sea = Objects.requireNonNull(sea, "sea must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    @Override
    public void apply(IslandStart start) {
        int surface = start.y() - sea.belowIsland();
        service.add(start.islandId(), surface);
        Material floor = Material.matchMaterial(sea.floor());
        if (floor == null || !floor.isBlock()) {
            LOGGER.warning(() -> "modules/acidisland.conf names the floor '" + sea.floor() + "', which is no block."
                    + " The sea stands on sand.");
            floor = Material.SAND;
        }
        World world = start.world();
        int minX = start.centerX() - sea.radius();
        int maxX = start.centerX() + sea.radius();
        int minZ = start.centerZ() - sea.radius();
        int maxZ = start.centerZ() + sea.radius();
        Material bed = floor;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                scheduler.onRegion(
                        world.getName(), chunkX, chunkZ, () -> fill(world, fromX, toX, fromZ, toZ, surface, bed));
            }
        }
    }

    private void fill(World world, int fromX, int toX, int fromZ, int toZ, int surface, Material floor) {
        int bottom = surface - sea.depth() + 1;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                setIfAir(world.getBlockAt(x, bottom - 1, z), floor);
                for (int y = bottom; y <= surface; y++) {
                    setIfAir(world.getBlockAt(x, y, z), Material.WATER);
                }
            }
        }
    }

    private static void setIfAir(Block block, Material material) {
        if (block.getType().isAir()) {
            // No physics: a sea laid a chunk at a time must not flow into the chunk laid next.
            block.setType(material, false);
        }
    }
}
