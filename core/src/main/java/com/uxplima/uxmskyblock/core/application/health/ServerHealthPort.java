package com.uxplima.uxmskyblock.core.application.health;

/**
 * What this node can say about its own state, for the health endpoint to answer with.
 *
 * <p>A port because the numbers live on the server platform and the REST adapter must not reach it:
 * the adapter answers an HTTP request and knows nothing about ticks or worlds.
 */
public interface ServerHealthPort {

    /** Ticks per second over the last minute, as the server measures it. */
    double ticksPerSecond();

    /** How many islands are live on this node. */
    int activeIslandCount();

    /**
     * The share of island lookups the protection path answered from memory, between 0 and 1. A
     * number well below 1 means the hot path is reaching for the database and the node is not warm.
     */
    double spatialCacheHitRatio();
}
