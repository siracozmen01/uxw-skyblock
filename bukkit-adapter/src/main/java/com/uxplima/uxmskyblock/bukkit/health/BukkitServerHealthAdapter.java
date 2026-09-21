package com.uxplima.uxmskyblock.bukkit.health;

import java.util.Objects;

import org.bukkit.Bukkit;

import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.health.ServerHealthPort;

/** Answers the health port from the running server and the spatial index this node keeps warm. */
public final class BukkitServerHealthAdapter implements ServerHealthPort {

    private final SpatialIslandIndex spatialIndex;

    public BukkitServerHealthAdapter(SpatialIslandIndex spatialIndex) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "spatialIndex must not be null");
    }

    @Override
    public double ticksPerSecond() {
        double[] tps = Bukkit.getTPS();
        return tps.length > 0 ? tps[0] : 0.0;
    }

    @Override
    public int activeIslandCount() {
        return spatialIndex.cachedIslandCount();
    }

    @Override
    public double spatialCacheHitRatio() {
        return spatialIndex.hitRatio();
    }
}
