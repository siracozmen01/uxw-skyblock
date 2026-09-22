package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How long the plugin keeps what it told a player, and how often it clears that out.
 *
 * <p>Nothing ever wrote a notification and nothing ever deleted one, so neither number had anywhere
 * to be set. Both exist now, because a table that only grows is the other half of a table that has
 * finally started being written to.
 */
public record NotificationConfiguration(Duration readRetention, Duration sweepInterval, Duration activityRetention) {

    /** How long a notification a player has already read is kept. */
    public static final Duration DEFAULT_READ_RETENTION = Duration.ofDays(7);

    /** How often the read notifications are swept. */
    public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofHours(6);

    /**
     * How long an island's activity feed keeps a line.
     *
     * <p>A feed is a digest of what happened lately, not a ledger, so it is kept for longer than a
     * notice somebody has read and shorter than forever.
     */
    public static final Duration DEFAULT_ACTIVITY_RETENTION = Duration.ofDays(30);

    public NotificationConfiguration {
        Objects.requireNonNull(readRetention, "readRetention must not be null");
        Objects.requireNonNull(sweepInterval, "sweepInterval must not be null");
        if (readRetention.isNegative()) {
            throw new IllegalArgumentException("read-retention must not be negative: " + readRetention);
        }
        if (sweepInterval.isNegative() || sweepInterval.isZero()) {
            throw new IllegalArgumentException("sweep-interval must be positive: " + sweepInterval);
        }
        Objects.requireNonNull(activityRetention, "activityRetention must not be null");
        if (activityRetention.isNegative() || activityRetention.isZero()) {
            throw new IllegalArgumentException("activity-retention must be positive: " + activityRetention);
        }
    }

    public static NotificationConfiguration defaultConfiguration() {
        return new NotificationConfiguration(
                DEFAULT_READ_RETENTION, DEFAULT_SWEEP_INTERVAL, DEFAULT_ACTIVITY_RETENTION);
    }

    public static NotificationConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("notifications");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }
        return new NotificationConfiguration(
                readDuration(node.node("read-retention"), DEFAULT_READ_RETENTION),
                readDuration(node.node("sweep-interval"), DEFAULT_SWEEP_INTERVAL),
                readDuration(node.node("activity-retention"), DEFAULT_ACTIVITY_RETENTION));
    }

    /**
     * Reads a window, falling back rather than refusing to start.
     *
     * <p>A server that will not boot over a retention window is a worse outcome than one that boots
     * with the shipped number.
     */
    private static Duration readDuration(ConfigurationNode node, Duration fallback) {
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Durations.parse(raw.strip());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
