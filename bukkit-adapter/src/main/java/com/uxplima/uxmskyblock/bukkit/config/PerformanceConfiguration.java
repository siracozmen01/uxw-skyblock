package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Performance and adaptive backpressure configuration (Section 2.42).
 */
public record PerformanceConfiguration(
        boolean adaptiveThrottle,
        double tpsThreshold,
        int normalBlocksPerTick,
        int throttledBlocksPerTick,
        int normalChunksPerSec,
        int throttledChunksPerSec) {

    public static final boolean DEFAULT_ADAPTIVE_THROTTLE = true;
    public static final double DEFAULT_TPS_THRESHOLD = 19.5;
    public static final int DEFAULT_NORMAL_BLOCKS = 128;
    public static final int DEFAULT_THROTTLED_BLOCKS = 16;
    public static final int DEFAULT_NORMAL_CHUNKS = 100;
    public static final int DEFAULT_THROTTLED_CHUNKS = 20;

    public static PerformanceConfiguration defaultConfiguration() {
        return new PerformanceConfiguration(
                DEFAULT_ADAPTIVE_THROTTLE,
                DEFAULT_TPS_THRESHOLD,
                DEFAULT_NORMAL_BLOCKS,
                DEFAULT_THROTTLED_BLOCKS,
                DEFAULT_NORMAL_CHUNKS,
                DEFAULT_THROTTLED_CHUNKS);
    }

    public static PerformanceConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        if (rootNode.virtual() || rootNode.empty()) {
            return defaultConfiguration();
        }

        boolean adaptiveThrottle = rootNode.node("adaptive-throttle").getBoolean(DEFAULT_ADAPTIVE_THROTTLE);
        double tpsThreshold = rootNode.node("tps-threshold").getDouble(DEFAULT_TPS_THRESHOLD);
        int normalBlocks = rootNode.node("normal-blocks-per-tick").getInt(DEFAULT_NORMAL_BLOCKS);
        int throttledBlocks = rootNode.node("throttled-blocks-per-tick").getInt(DEFAULT_THROTTLED_BLOCKS);
        int normalChunks = rootNode.node("normal-deletion-chunks-per-sec").getInt(DEFAULT_NORMAL_CHUNKS);
        int throttledChunks = rootNode.node("throttled-deletion-chunks-per-sec").getInt(DEFAULT_THROTTLED_CHUNKS);

        return new PerformanceConfiguration(
                adaptiveThrottle, tpsThreshold, normalBlocks, throttledBlocks, normalChunks, throttledChunks);
    }
}
