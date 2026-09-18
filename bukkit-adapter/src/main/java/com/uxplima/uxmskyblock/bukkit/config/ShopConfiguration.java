package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for the dynamic shop pricing engine, curve parameters, and sync intervals.
 */
public record ShopConfiguration(
        boolean enabled,
        double dampingFactor,
        Duration asyncRefreshInterval,
        long defaultStockBaseline,
        double defaultElasticity) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_DAMPING_FACTOR = 0.85;
    public static final Duration DEFAULT_ASYNC_REFRESH_INTERVAL = Duration.ofMinutes(2);
    public static final long DEFAULT_STOCK_BASELINE = 100L;
    public static final double DEFAULT_ELASTICITY = 0.5;

    public ShopConfiguration {
        Objects.requireNonNull(asyncRefreshInterval, "asyncRefreshInterval must not be null");
        if (dampingFactor <= 0.0 || dampingFactor > 1.0) {
            throw new IllegalArgumentException("dampingFactor must be in range (0.0, 1.0]: " + dampingFactor);
        }
        if (asyncRefreshInterval.isNegative() || asyncRefreshInterval.isZero()) {
            throw new IllegalArgumentException("asyncRefreshInterval must be positive: " + asyncRefreshInterval);
        }
        if (defaultStockBaseline <= 0) {
            throw new IllegalArgumentException("defaultStockBaseline must be positive: " + defaultStockBaseline);
        }
        if (defaultElasticity <= 0.0) {
            throw new IllegalArgumentException("defaultElasticity must be positive: " + defaultElasticity);
        }
    }

    public static ShopConfiguration defaultConfiguration() {
        return new ShopConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_DAMPING_FACTOR,
                DEFAULT_ASYNC_REFRESH_INTERVAL,
                DEFAULT_STOCK_BASELINE,
                DEFAULT_ELASTICITY);
    }

    public static ShopConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("shop");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        double damping = node.node("damping-factor").getDouble(DEFAULT_DAMPING_FACTOR);

        String refreshRaw = node.node("async-refresh-interval").getString();
        Duration refreshInterval = refreshRaw != null && !refreshRaw.isBlank()
                ? Durations.parse(refreshRaw)
                : Duration.ofMinutes(node.node("async-refresh-interval-minutes").getInt(2));

        long stockBaseline = node.node("default-stock-baseline").getLong(DEFAULT_STOCK_BASELINE);
        double elasticity = node.node("default-elasticity").getDouble(DEFAULT_ELASTICITY);

        return new ShopConfiguration(enabled, damping, refreshInterval, stockBaseline, elasticity);
    }
}
