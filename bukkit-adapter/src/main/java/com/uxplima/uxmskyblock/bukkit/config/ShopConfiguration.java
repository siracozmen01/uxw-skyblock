package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The shop: what it sells, at what price, and how hard the price moves.
 *
 * <p>The pricing engine was built, tuned and registered as a service, and nothing ever put an item
 * in it. It had a damping factor, an elasticity and a stock baseline for items that did not exist,
 * so every price it could be asked for was absent. The items live here now, and an operator adds one
 * by adding a block to the file.
 *
 * @param items every commodity the shop trades, by the material's name
 */
public record ShopConfiguration(
        boolean enabled,
        double dampingFactor,
        Duration asyncRefreshInterval,
        long defaultStockBaseline,
        double defaultElasticity,
        Map<String, PricingCurve> items) {

    private static final Logger LOGGER = Logger.getLogger(ShopConfiguration.class.getName());

    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_DAMPING_FACTOR = 0.85;
    public static final Duration DEFAULT_ASYNC_REFRESH_INTERVAL = Duration.ofMinutes(2);
    public static final long DEFAULT_STOCK_BASELINE = 100L;
    public static final double DEFAULT_ELASTICITY = 0.5;

    public ShopConfiguration {
        Objects.requireNonNull(asyncRefreshInterval, "asyncRefreshInterval must not be null");
        items = items == null ? Map.of() : Map.copyOf(items);
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
                DEFAULT_ELASTICITY,
                Map.of());
    }

    /** The five-argument shape, for a caller that names no items. */
    public ShopConfiguration(
            boolean enabled,
            double dampingFactor,
            Duration asyncRefreshInterval,
            long defaultStockBaseline,
            double defaultElasticity) {
        this(enabled, dampingFactor, asyncRefreshInterval, defaultStockBaseline, defaultElasticity, Map.of());
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

        Map<String, PricingCurve> items = readItems(node.node("items"), stockBaseline, elasticity);

        return new ShopConfiguration(enabled, damping, refreshInterval, stockBaseline, elasticity, items);
    }

    /**
     * Reads the commodities, one block each.
     *
     * <p>Only the base price has to be written. A floor and a ceiling default to a half and a double
     * of it, and the elasticity and the stock baseline fall back to the ones the shop sets for
     * everything, so a line an operator does not care about is a line they do not write.
     *
     * <p>An item whose numbers do not make a curve is left out with its reason logged, because a
     * shop that refuses to load over one bad block sells nothing at all.
     */
    private static Map<String, PricingCurve> readItems(
            ConfigurationNode itemsNode, long defaultStockBaseline, double defaultElasticity) {
        if (itemsNode.virtual() || !itemsNode.isMap()) {
            return Map.of();
        }
        Map<String, PricingCurve> items = new LinkedHashMap<>();
        for (var entry : itemsNode.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey()).trim().toUpperCase(java.util.Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            ConfigurationNode item = entry.getValue();
            long base = item.node("base-price").getLong(0L);
            if (base <= 0) {
                LOGGER.log(Level.WARNING, "Shop item {0} names no positive base-price and is not sold.", key);
                continue;
            }
            long floor = item.node("floor-price").getLong(Math.max(1L, base / 2));
            long ceiling = item.node("ceiling-price").getLong(base * 2);
            double elasticity = item.node("elasticity").getDouble(defaultElasticity);
            long baseline = item.node("stock-baseline").getLong(defaultStockBaseline);
            try {
                items.put(key, new PricingCurve(base, floor, ceiling, elasticity, baseline));
            } catch (IllegalArgumentException wrong) {
                LOGGER.log(
                        Level.WARNING,
                        "Shop item {0} is not sold, because its prices do not make a curve: {1}",
                        new Object[] {key, wrong.getMessage()});
            }
        }
        return Map.copyOf(items);
    }
}
