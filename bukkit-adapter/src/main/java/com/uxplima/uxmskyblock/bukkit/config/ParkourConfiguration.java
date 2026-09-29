package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/parkour.conf}: whether islands can be Parkour courses, which blocks mark a course's
 * start, checkpoints and finish, and how a run is kept.
 */
public record ParkourConfiguration(boolean enabled, Markers markers, Runs runs) {

    private static final Logger LOGGER = Logger.getLogger(ParkourConfiguration.class.getName());

    /**
     * What marks a course.
     *
     * @param plate the plate a run starts and ends on
     * @param startUnder the block under a plate that makes it the start
     * @param finishUnder the block under a plate that makes it the finish
     * @param checkpoint the plate that is a checkpoint wherever it stands
     */
    public record Markers(Material plate, Material startUnder, Material finishUnder, Material checkpoint) {

        public static final Markers SHIPPED = new Markers(
                Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
                Material.EMERALD_BLOCK,
                Material.GOLD_BLOCK,
                Material.HEAVY_WEIGHTED_PRESSURE_PLATE);

        public Markers {
            Objects.requireNonNull(plate, "plate must not be null");
            Objects.requireNonNull(startUnder, "startUnder must not be null");
            Objects.requireNonNull(finishUnder, "finishUnder must not be null");
            Objects.requireNonNull(checkpoint, "checkpoint must not be null");
            if (startUnder == finishUnder || plate == checkpoint) {
                throw new IllegalArgumentException(
                        "the start and the finish, and the plate and the checkpoint, must differ");
            }
        }
    }

    /**
     * How a run is kept.
     *
     * @param fallDepth how far below the last checkpoint a runner may fall before being put back on it
     * @param timeLimit how long a run may take before it is dropped
     */
    public record Runs(int fallDepth, Duration timeLimit) {

        public static final Runs SHIPPED = new Runs(8, Duration.ofMinutes(30));

        public Runs {
            Objects.requireNonNull(timeLimit, "timeLimit must not be null");
            if (fallDepth < 1 || timeLimit.toSeconds() < 1) {
                throw new IllegalArgumentException("the fall depth and the time limit must be above 0");
            }
        }
    }

    public ParkourConfiguration {
        Objects.requireNonNull(markers, "markers must not be null");
        Objects.requireNonNull(runs, "runs must not be null");
    }

    public static ParkourConfiguration defaultConfiguration() {
        return new ParkourConfiguration(true, Markers.SHIPPED, Runs.SHIPPED);
    }

    public static ParkourConfiguration load(ConfigurationNode root) {
        ConfigurationNode written = root.node("markers");
        Markers markers;
        try {
            markers = new Markers(
                    block(written.node("plate").getString(""), Markers.SHIPPED.plate()),
                    block(written.node("start-under").getString(""), Markers.SHIPPED.startUnder()),
                    block(written.node("finish-under").getString(""), Markers.SHIPPED.finishUnder()),
                    block(written.node("checkpoint").getString(""), Markers.SHIPPED.checkpoint()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf markers: " + e.getMessage() + ". The shipped markers are used.");
            markers = Markers.SHIPPED;
        }
        ConfigurationNode run = root.node("runs");
        Runs runs;
        try {
            runs = new Runs(
                    run.node("fall-depth").getInt(Runs.SHIPPED.fallDepth()),
                    AcidIslandConfiguration.durationOf(run.node("time-limit").getString(""), Runs.SHIPPED.timeLimit()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/parkour.conf runs: " + e.getMessage() + ". The shipped runs are used.");
            runs = Runs.SHIPPED;
        }
        return new ParkourConfiguration(root.node("enabled").getBoolean(true), markers, runs);
    }

    private static Material block(String written, Material shipped) {
        if (written.isBlank()) {
            return shipped;
        }
        Material material = Material.matchMaterial(written.trim().toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException(written + " is no block");
        }
        return material;
    }
}
