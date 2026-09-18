package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island warps, safe teleport anti-trap engine parameters, and warmup timers.
 */
public record WarpConfiguration(
        boolean enabled,
        int baseWarpLimit,
        int searchRadius,
        Duration warmupDuration,
        boolean cancelOnMove,
        Duration cooldownDuration) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_BASE_WARP_LIMIT = 2;
    public static final int DEFAULT_SEARCH_RADIUS = 5;
    public static final Duration DEFAULT_WARMUP = Duration.ofSeconds(3);
    public static final boolean DEFAULT_CANCEL_ON_MOVE = true;
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(5);

    public WarpConfiguration {
        Objects.requireNonNull(warmupDuration, "warmupDuration must not be null");
        Objects.requireNonNull(cooldownDuration, "cooldownDuration must not be null");
        if (baseWarpLimit < 1) {
            throw new IllegalArgumentException("baseWarpLimit must be >= 1: " + baseWarpLimit);
        }
        if (searchRadius < 0) {
            throw new IllegalArgumentException("searchRadius cannot be negative: " + searchRadius);
        }
    }

    public static WarpConfiguration defaultConfiguration() {
        return new WarpConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_BASE_WARP_LIMIT,
                DEFAULT_SEARCH_RADIUS,
                DEFAULT_WARMUP,
                DEFAULT_CANCEL_ON_MOVE,
                DEFAULT_COOLDOWN);
    }

    public static WarpConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("warps");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        int baseLimit = node.node("base-warp-limit").getInt(DEFAULT_BASE_WARP_LIMIT);
        int radius = node.node("search-radius").getInt(DEFAULT_SEARCH_RADIUS);

        String warmupRaw = node.node("warmup-seconds").getString();
        Duration warmup = DEFAULT_WARMUP;
        if (warmupRaw != null && !warmupRaw.isBlank()) {
            warmup = warmupRaw.matches("^\\d+$")
                    ? Duration.ofSeconds(Long.parseLong(warmupRaw))
                    : Durations.parse(warmupRaw);
        }

        boolean cancelOnMove = node.node("cancel-on-move").getBoolean(DEFAULT_CANCEL_ON_MOVE);

        String cooldownRaw = node.node("cooldown-seconds").getString();
        Duration cooldown = DEFAULT_COOLDOWN;
        if (cooldownRaw != null && !cooldownRaw.isBlank()) {
            cooldown = cooldownRaw.matches("^\\d+$")
                    ? Duration.ofSeconds(Long.parseLong(cooldownRaw))
                    : Durations.parse(cooldownRaw);
        }

        return new WarpConfiguration(enabled, baseLimit, radius, warmup, cancelOnMove, cooldown);
    }
}
