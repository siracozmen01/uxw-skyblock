package com.uxplima.uxmskyblock.bukkit.webmap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Fallback no-operation web map adapter when no compatible web map provider is installed.
 */
public final class NoOpWebMapAdapter implements WebMapAdapter {

    public static final NoOpWebMapAdapter INSTANCE = new NoOpWebMapAdapter();

    private NoOpWebMapAdapter() {}

    @Override
    public void registerIslandMarker(IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        // No-op
    }

    @Override
    public void updateIslandMarker(IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        // No-op
    }

    @Override
    public void removeIslandMarker(IslandId islandId) {
        // No-op
    }

    @Override
    public String providerName() {
        return "NONE";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
