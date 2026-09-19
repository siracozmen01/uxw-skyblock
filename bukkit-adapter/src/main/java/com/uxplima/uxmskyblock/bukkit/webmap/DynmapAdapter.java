package com.uxplima.uxmskyblock.bukkit.webmap;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Web map adapter integrating with the Dynmap plugin (Section 2.42).
 *
 * <p>Uses reflection to avoid compile-time or runtime hard dependencies on the Dynmap JAR.
 */
public final class DynmapAdapter implements WebMapAdapter {

    private static final Logger LOGGER = Logger.getLogger(DynmapAdapter.class.getName());
    private static final String MARKER_SET_ID = "uxmskyblock.islands";
    private static final String MARKER_SET_LABEL = "Islands";

    private final Plugin plugin;

    public DynmapAdapter(Plugin plugin) {
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
            Object markerSet = getOrCreateMarkerSet();
            if (markerSet != null) {
                String markerId = "island_" + islandId.value();
                // markerSet.createMarker(id, label, world, x, y, z, icon, isPersistent)
                markerSet.getClass().getMethod(
                        "createMarker", String.class, String.class, String.class,
                        double.class, double.class, double.class,
                        Class.forName("org.dynmap.markers.MarkerIcon"), boolean.class
                ).invoke(markerSet, markerId, islandName, worldName, x, y, z, null, false);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to register Dynmap marker for island " + islandId, e);
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
            Object markerSet = getOrCreateMarkerSet();
            if (markerSet != null) {
                String markerId = "island_" + islandId.value();
                Object marker = markerSet.getClass().getMethod("findMarker", String.class).invoke(markerSet, markerId);
                if (marker != null) {
                    marker.getClass().getMethod("deleteMarker").invoke(marker);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to remove Dynmap marker for island " + islandId, e);
        }
    }

    @Override
    public String providerName() {
        return "DYNMAP";
    }

    @Override
    public boolean isAvailable() {
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
                return false;
            }
            Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
            return dynmap != null && dynmap.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private Object getOrCreateMarkerSet() {
        try {
            Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
            if (dynmap == null || !dynmap.isEnabled()) {
                return null;
            }
            Object markerApi = dynmap.getClass().getMethod("getMarkerAPI").invoke(dynmap);
            if (markerApi == null) {
                return null;
            }
            Object set = markerApi.getClass().getMethod("getMarkerSet", String.class).invoke(markerApi, MARKER_SET_ID);
            if (set == null) {
                set = markerApi.getClass().getMethod("createMarkerSet", String.class, String.class, java.util.Set.class, boolean.class)
                        .invoke(markerApi, MARKER_SET_ID, MARKER_SET_LABEL, null, false);
            }
            return set;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Dynmap marker API not accessible", e);
            return null;
        }
    }
}
