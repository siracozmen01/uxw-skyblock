package com.uxplima.uxmskyblock.bukkit.health;

import java.util.Objects;

import org.bukkit.Bukkit;

import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.health.ServerHealthPort;

/** Answers the health port from the running server and the spatial index this node keeps warm. */
public final class BukkitServerHealthAdapter implements ServerHealthPort {

    private final SpatialIslandIndex spatialIndex;
    private final java.util.function.Supplier<double[]> serverTps;

    public BukkitServerHealthAdapter(SpatialIslandIndex spatialIndex) {
        this(spatialIndex, Bukkit::getTPS);
    }

    /** Package private so a test can stand in for a server that keeps no single tick rate. */
    BukkitServerHealthAdapter(SpatialIslandIndex spatialIndex, java.util.function.Supplier<double[]> serverTps) {
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "spatialIndex must not be null");
        this.serverTps = Objects.requireNonNull(serverTps, "serverTps must not be null");
    }

    /**
     * The server's tick rate, or NaN where there is none to read.
     *
     * <p>Folia ticks each region on its own and refuses the question off a region's thread, which is
     * where a REST call arrives: the health endpoint answered every call with a server error.
     */
    @Override
    public double ticksPerSecond() {
        try {
            double[] tps = serverTps.get();
            return tps.length > 0 ? tps[0] : Double.NaN;
        } catch (UnsupportedOperationException perRegion) {
            return Double.NaN;
        }
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
