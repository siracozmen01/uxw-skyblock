package com.uxplima.uxmskyblock.bukkit.worth;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Event listener tracking live block placements, breaks, and spawner changes across Folia regions.
 *
 * <p>Updates {@link IslandWorthService} histograms in amortized O(1) time without blocking region threads.
 */
public final class IslandWorthListener implements Listener {

    private final IslandWorthService worthService;
    private final IslandProtectionListener protectionListener;

    public IslandWorthListener(IslandWorthService worthService, IslandProtectionListener protectionListener) {
        this.worthService = Objects.requireNonNull(worthService, "worthService must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Optional<Island> islandOpt = protectionListener.findIslandAt(block.getLocation());
        if (islandOpt.isEmpty()) {
            return;
        }

        IslandId islandId = islandOpt.get().id();
        String matKey = block.getType().getKey().toString();
        worthService.recordBlockPlace(islandId, matKey, 1);

        if (block.getState() instanceof CreatureSpawner spawner) {
            String entityType = spawner.getSpawnedType() != null
                    ? spawner.getSpawnedType().getKey().toString()
                    : "minecraft:pig";
            worthService.recordSpawnerPlace(islandId, entityType);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Optional<Island> islandOpt = protectionListener.findIslandAt(block.getLocation());
        if (islandOpt.isEmpty()) {
            return;
        }

        IslandId islandId = islandOpt.get().id();
        String matKey = block.getType().getKey().toString();
        worthService.recordBlockBreak(islandId, matKey, 1);

        if (block.getState() instanceof CreatureSpawner spawner) {
            String entityType = spawner.getSpawnedType() != null
                    ? spawner.getSpawnedType().getKey().toString()
                    : "minecraft:pig";
            worthService.recordSpawnerBreak(islandId, entityType);
        }
    }
}
