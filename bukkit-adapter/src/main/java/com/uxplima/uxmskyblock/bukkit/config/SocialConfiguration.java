package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for social discovery, ratings, dwell times, and interactive guestbooks.
 */
public record SocialConfiguration(
        Duration minDwellTime, int priorWeight, double priorMean, int maxPinned, int maxMessageLength) {

    public static final Duration DEFAULT_MIN_DWELL_TIME = Duration.ofSeconds(30);
    public static final int DEFAULT_PRIOR_WEIGHT = 5;
    public static final double DEFAULT_PRIOR_MEAN = 3.0;
    public static final int DEFAULT_MAX_PINNED = 3;
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 256;

    public SocialConfiguration {
        Objects.requireNonNull(minDwellTime, "minDwellTime must not be null");
        if (priorWeight < 0) {
            throw new IllegalArgumentException("priorWeight cannot be negative: " + priorWeight);
        }
        if (maxPinned < 0) {
            throw new IllegalArgumentException("maxPinned cannot be negative: " + maxPinned);
        }
        if (maxMessageLength <= 0) {
            throw new IllegalArgumentException("maxMessageLength must be positive: " + maxMessageLength);
        }
    }

    public static SocialConfiguration defaultConfiguration() {
        return new SocialConfiguration(
                DEFAULT_MIN_DWELL_TIME,
                DEFAULT_PRIOR_WEIGHT,
                DEFAULT_PRIOR_MEAN,
                DEFAULT_MAX_PINNED,
                DEFAULT_MAX_MESSAGE_LENGTH);
    }

    public static SocialConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("social");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        String dwellRaw = node.node("min-dwell-time").getString();
        Duration minDwellTime =
                dwellRaw != null && !dwellRaw.isBlank() ? Durations.parse(dwellRaw) : DEFAULT_MIN_DWELL_TIME;

        int priorWeight = node.node("prior-weight").getInt(DEFAULT_PRIOR_WEIGHT);
        double priorMean = node.node("prior-mean").getDouble(DEFAULT_PRIOR_MEAN);
        int maxPinned = node.node("max-pinned").getInt(DEFAULT_MAX_PINNED);
        int maxMsgLen = node.node("max-message-length").getInt(DEFAULT_MAX_MESSAGE_LENGTH);

        return new SocialConfiguration(minDwellTime, priorWeight, priorMean, maxPinned, maxMsgLen);
    }
}
