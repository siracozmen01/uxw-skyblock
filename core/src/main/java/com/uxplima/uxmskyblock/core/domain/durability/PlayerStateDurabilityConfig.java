package com.uxplima.uxmskyblock.core.domain.durability;

import java.time.Duration;
import java.util.Objects;

/**
 * Pure domain configuration record defining player-state durability policies.
 *
 * <p>Encapsulates the canonical durability mode and operator-configurable routine ambient
 * checkpoint interval.
 *
 * @param durabilityMode the durability model variant (e.g. HYBRID)
 * @param ambientCheckpointInterval the routine ambient checkpoint interval (must be positive)
 */
public record PlayerStateDurabilityConfig(DurabilityMode durabilityMode, Duration ambientCheckpointInterval) {

    /**
     * The default ambient checkpoint interval (60 seconds) in accordance with frozen architecture.
     * Note that this is a configuration default, not a hard mathematical loss guarantee.
     */
    public static final Duration DEFAULT_AMBIENT_CHECKPOINT_INTERVAL = Duration.ofSeconds(60);

    /**
     * The canonical product durability mode for V1.
     */
    public static final DurabilityMode DEFAULT_DURABILITY_MODE = DurabilityMode.HYBRID;

    /**
     * Compact constructor validating non-nullness and positive duration invariants.
     */
    public PlayerStateDurabilityConfig {
        Objects.requireNonNull(durabilityMode, "durabilityMode must not be null");
        Objects.requireNonNull(ambientCheckpointInterval, "ambientCheckpointInterval must not be null");
        if (ambientCheckpointInterval.isNegative() || ambientCheckpointInterval.isZero()) {
            throw new IllegalArgumentException(
                    "ambientCheckpointInterval must be positive, but was: " + ambientCheckpointInterval);
        }
    }

    /**
     * Returns the canonical default durability policy (HYBRID with 60-second checkpoint cadence).
     *
     * @return the default player-state durability policy
     */
    public static PlayerStateDurabilityConfig defaultPolicy() {
        return new PlayerStateDurabilityConfig(DEFAULT_DURABILITY_MODE, DEFAULT_AMBIENT_CHECKPOINT_INTERVAL);
    }

    /**
     * Creates a durability policy with default {@link DurabilityMode#HYBRID} and a custom interval.
     *
     * @param ambientCheckpointInterval the custom positive checkpoint interval
     * @return the player-state durability policy
     */
    public static PlayerStateDurabilityConfig of(Duration ambientCheckpointInterval) {
        return new PlayerStateDurabilityConfig(DEFAULT_DURABILITY_MODE, ambientCheckpointInterval);
    }

    /**
     * Creates a durability policy with an explicit durability mode and custom interval.
     *
     * @param durabilityMode the durability mode
     * @param ambientCheckpointInterval the custom positive checkpoint interval
     * @return the player-state durability policy
     */
    public static PlayerStateDurabilityConfig of(DurabilityMode durabilityMode, Duration ambientCheckpointInterval) {
        return new PlayerStateDurabilityConfig(durabilityMode, ambientCheckpointInterval);
    }
}
