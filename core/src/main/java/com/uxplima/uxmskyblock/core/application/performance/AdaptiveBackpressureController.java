package com.uxplima.uxmskyblock.core.application.performance;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Enterprise Folia adaptive backpressure controller (Section 2.42).
 * Dynamically adjusts block pasting and chunk deletion batch sizes based on live TPS feedback
 * to protect region tick cadence from degradation.
 */
public final class AdaptiveBackpressureController {

    public static final double DEFAULT_TPS_THRESHOLD = 19.5;
    public static final int DEFAULT_NORMAL_BLOCKS_PER_TICK = 128;
    public static final int DEFAULT_THROTTLED_BLOCKS_PER_TICK = 16;
    public static final int DEFAULT_NORMAL_CHUNKS_PER_SEC = 100;
    public static final int DEFAULT_THROTTLED_CHUNKS_PER_SEC = 20;

    private final DoubleSupplier tpsSupplier;
    private final boolean adaptiveThrottleEnabled;
    private final double tpsThreshold;
    private final int normalBlocksPerTick;
    private final int throttledBlocksPerTick;
    private final int normalChunksPerSec;
    private final int throttledChunksPerSec;

    public AdaptiveBackpressureController(DoubleSupplier tpsSupplier) {
        this(
                tpsSupplier,
                true,
                DEFAULT_TPS_THRESHOLD,
                DEFAULT_NORMAL_BLOCKS_PER_TICK,
                DEFAULT_THROTTLED_BLOCKS_PER_TICK,
                DEFAULT_NORMAL_CHUNKS_PER_SEC,
                DEFAULT_THROTTLED_CHUNKS_PER_SEC);
    }

    public AdaptiveBackpressureController(
            DoubleSupplier tpsSupplier,
            boolean adaptiveThrottleEnabled,
            double tpsThreshold,
            int normalBlocksPerTick,
            int throttledBlocksPerTick,
            int normalChunksPerSec,
            int throttledChunksPerSec) {
        this.tpsSupplier = Objects.requireNonNull(tpsSupplier, "tpsSupplier must not be null");
        this.adaptiveThrottleEnabled = adaptiveThrottleEnabled;
        this.tpsThreshold = tpsThreshold;
        this.normalBlocksPerTick = normalBlocksPerTick;
        this.throttledBlocksPerTick = throttledBlocksPerTick;
        this.normalChunksPerSec = normalChunksPerSec;
        this.throttledChunksPerSec = throttledChunksPerSec;
    }

    /**
     * Checks if current TPS indicates regional backpressure.
     */
    public boolean isThrottlingActive() {
        if (!adaptiveThrottleEnabled) {
            return false;
        }
        double currentTps = tpsSupplier.getAsDouble();
        return currentTps < tpsThreshold;
    }

    /**
     * Resolves the maximum block pasting batch budget for the current tick.
     */
    public int resolveBlockPasteBatchSize() {
        return isThrottlingActive() ? throttledBlocksPerTick : normalBlocksPerTick;
    }

    /**
     * Resolves the maximum chunk deletion rate budget per second.
     */
    public int resolveChunkDeletionRate() {
        return isThrottlingActive() ? throttledChunksPerSec : normalChunksPerSec;
    }

    public double currentTps() {
        return tpsSupplier.getAsDouble();
    }

    public double tpsThreshold() {
        return tpsThreshold;
    }

    public boolean isAdaptiveThrottleEnabled() {
        return adaptiveThrottleEnabled;
    }
}
