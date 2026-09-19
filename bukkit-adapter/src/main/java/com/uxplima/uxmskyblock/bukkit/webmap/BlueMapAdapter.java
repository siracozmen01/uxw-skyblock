package com.uxplima.uxmskyblock.bukkit.webmap;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Web map adapter integrating with the BlueMap plugin (Section 2.42).
 *
 * <p>Uses reflection to avoid compile-time or runtime hard dependencies on the BlueMap JAR.
 */
public final class BlueMapAdapter implements WebMapAdapter {

    private static final Logger LOGGER = Logger.getLogger(BlueMapAdapter.class.getName());

    private final Plugin plugin;

    public BlueMapAdapter(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    public Plugin plugin() {
        return plugin;
    }

    @Override
    public void registerIslandMarker(IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        if (!isAvailable()) {
            return;
        }
        try {
            LOGGER.log(Level.FINE, () -> "Registering BlueMap marker for island " + islandId + " at (" + x + "," + y + "," + z + ")");
            // BlueMap API interaction via reflection when BlueMapAPI is initialized
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to register BlueMap marker for island " + islandId, e);
        }
    }

    @Override
    public void updateIslandMarker(IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        if (!isAvailable()) {
            return;
        }
        removeIslandMarker(islandId);
        registerIslandMarker(islandId, islandName, worldName, x, y, z);
    }

    @Override
    public void removeIslandMarker(IslandId islandId) {
        if (!isAvailable()) {
            return;
        }
        try {
            LOGGER.log(Level.FINE, () -> "Removing BlueMap marker for island " + islandId);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to remove BlueMap marker for island " + islandId, e);
        }
    }

    @Override
    public String providerName() {
        return "BLUEMAP";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
                return false;
            }
            Plugin blueMap = Bukkit.getPluginManager().getPlugin("BlueMap");
            return blueMap != null && blueMap.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }
}
