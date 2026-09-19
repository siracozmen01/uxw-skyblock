package com.uxplima.uxmskyblock.bukkit.world;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureSpawnEvent;

import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;

/**
 * Native Async Structure Suppression Listener (Section 2.42 item 5).
 * Cancels expensive void-world structure generation queries asynchronously
 * to prevent multi-second main/region thread tick freezes.
 */
public final class AsyncStructureSuppressionListener implements Listener {

    private final WorldConfiguration config;

    public AsyncStructureSuppressionListener(WorldConfiguration config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @SuppressWarnings({"deprecation", "removal"})
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncStructureSpawn(AsyncStructureSpawnEvent event) {
        if (event.getStructure() != null && event.getStructure().getKey() != null) {
            String key = event.getStructure().getKey().asString();
            if (config.isSuppressed(key)) {
                event.setCancelled(true);
            }
        }
    }

    public WorldConfiguration config() {
        return config;
    }
}
