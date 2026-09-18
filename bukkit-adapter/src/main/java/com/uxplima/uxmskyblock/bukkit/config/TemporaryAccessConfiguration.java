package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for temporary access grants subsystem (ACC-001).
 */
public record TemporaryAccessConfiguration(
        boolean enabled,
        Duration defaultDuration,
        Duration maxDuration,
        Duration purgeInterval,
        boolean enforceRulesetIsolation) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_DURATION = Duration.ofHours(1);
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofDays(1);
    public static final Duration DEFAULT_PURGE_INTERVAL = Duration.ofMinutes(1);
    public static final boolean DEFAULT_ENFORCE_RULESET_ISOLATION = true;

    public TemporaryAccessConfiguration {
        Objects.requireNonNull(defaultDuration, "defaultDuration must not be null");
        Objects.requireNonNull(maxDuration, "maxDuration must not be null");
        Objects.requireNonNull(purgeInterval, "purgeInterval must not be null");
        if (defaultDuration.isNegative() || defaultDuration.isZero()) {
            throw new IllegalArgumentException("defaultDuration must be positive: " + defaultDuration);
        }
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive: " + maxDuration);
        }
        if (purgeInterval.isNegative() || purgeInterval.isZero()) {
            throw new IllegalArgumentException("purgeInterval must be positive: " + purgeInterval);
        }
    }

    public static TemporaryAccessConfiguration defaultConfiguration() {
        return new TemporaryAccessConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_DURATION,
                DEFAULT_MAX_DURATION,
                DEFAULT_PURGE_INTERVAL,
                DEFAULT_ENFORCE_RULESET_ISOLATION);
    }

    public static TemporaryAccessConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("temporary-access");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);

        String defDurRaw = node.node("default-duration").getString();
        Duration defaultDuration =
                defDurRaw != null && !defDurRaw.isBlank() ? Durations.parse(defDurRaw) : DEFAULT_DURATION;

        String maxDurRaw = node.node("max-duration").getString();
        Duration maxDuration =
                maxDurRaw != null && !maxDurRaw.isBlank() ? Durations.parse(maxDurRaw) : DEFAULT_MAX_DURATION;

        String purgeRaw = node.node("purge-interval").getString();
        Duration purgeInterval =
                purgeRaw != null && !purgeRaw.isBlank() ? Durations.parse(purgeRaw) : DEFAULT_PURGE_INTERVAL;

        boolean enforceIsolation = node.node("enforce-ruleset-isolation").getBoolean(DEFAULT_ENFORCE_RULESET_ISOLATION);

        return new TemporaryAccessConfiguration(enabled, defaultDuration, maxDuration, purgeInterval, enforceIsolation);
    }
}
