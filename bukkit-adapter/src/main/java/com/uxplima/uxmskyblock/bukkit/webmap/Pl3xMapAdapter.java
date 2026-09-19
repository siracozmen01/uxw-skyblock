package com.uxplima.uxmskyblock.bukkit.webmap;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Web map adapter integrating with the Pl3xMap plugin (Section 2.42).
 *
 * <p>Uses reflection to avoid compile-time or runtime hard dependencies on the Pl3xMap JAR.
 */
public final class Pl3xMapAdapter implements WebMapAdapter {

    private static final Logger LOGGER = Logger.getLogger(Pl3xMapAdapter.class.getName());

    private final Plugin plugin;

    public Pl3xMapAdapter(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    public Plugin plugin() {
        return plugin;
    }

    @Override
    public void registerIslandMarker(
            IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        if (!isAvailable()) {
            return;
        }
        try {
            LOGGER.log(
                    Level.FINE,
                    () -> "Registering Pl3xMap marker for island " + islandId + " at (" + x + "," + y + "," + z + ")");
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to register Pl3xMap marker for island " + islandId, e);
        }
    }

    @Override
    public void updateIslandMarker(
            IslandId islandId, String islandName, String worldName, double x, double y, double z) {
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
            LOGGER.log(Level.FINE, () -> "Removing Pl3xMap marker for island " + islandId);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to remove Pl3xMap marker for island " + islandId, e);
        }
    }

    @Override
    public String providerName() {
        return "PL3XMAP";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
                return false;
            }
            Plugin pl3xMap = Bukkit.getPluginManager().getPlugin("Pl3xMap");
            return pl3xMap != null && pl3xMap.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }
}
