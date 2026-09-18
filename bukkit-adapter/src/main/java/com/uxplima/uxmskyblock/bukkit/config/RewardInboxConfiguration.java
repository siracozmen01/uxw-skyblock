package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for the offline reward inbox subsystem (RWD-001).
 */
public record RewardInboxConfiguration(
        boolean enabled,
        Duration defaultExpiration,
        Duration expiryCheckInterval,
        int maxInboxCapacity,
        boolean autoClaimOnJoin) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_DEFAULT_EXPIRATION = Duration.ofDays(30);
    public static final Duration DEFAULT_EXPIRY_CHECK_INTERVAL = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_INBOX_CAPACITY = 50;
    public static final boolean DEFAULT_AUTO_CLAIM_ON_JOIN = false;

    public RewardInboxConfiguration {
        Objects.requireNonNull(defaultExpiration, "defaultExpiration must not be null");
        Objects.requireNonNull(expiryCheckInterval, "expiryCheckInterval must not be null");
        if (defaultExpiration.isNegative() || defaultExpiration.isZero()) {
            throw new IllegalArgumentException("defaultExpiration must be positive: " + defaultExpiration);
        }
        if (expiryCheckInterval.isNegative() || expiryCheckInterval.isZero()) {
            throw new IllegalArgumentException("expiryCheckInterval must be positive: " + expiryCheckInterval);
        }
        if (maxInboxCapacity < 1) {
            throw new IllegalArgumentException("maxInboxCapacity must be positive: " + maxInboxCapacity);
        }
    }

    public static RewardInboxConfiguration defaultConfiguration() {
        return new RewardInboxConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_DEFAULT_EXPIRATION,
                DEFAULT_EXPIRY_CHECK_INTERVAL,
                DEFAULT_MAX_INBOX_CAPACITY,
                DEFAULT_AUTO_CLAIM_ON_JOIN);
    }

    public static RewardInboxConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("rewards");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);

        String defExpRaw = node.node("default-expiration").getString();
        Duration defaultExpiration =
                defExpRaw != null && !defExpRaw.isBlank() ? Durations.parse(defExpRaw) : DEFAULT_DEFAULT_EXPIRATION;

        String expiryIntRaw = node.node("expiry-check-interval").getString();
        Duration expiryCheckInterval = expiryIntRaw != null && !expiryIntRaw.isBlank()
                ? Durations.parse(expiryIntRaw)
                : DEFAULT_EXPIRY_CHECK_INTERVAL;

        int maxCapacity = node.node("max-inbox-capacity").getInt(DEFAULT_MAX_INBOX_CAPACITY);
        boolean autoClaim = node.node("auto-claim-on-join").getBoolean(DEFAULT_AUTO_CLAIM_ON_JOIN);

        return new RewardInboxConfiguration(enabled, defaultExpiration, expiryCheckInterval, maxCapacity, autoClaim);
    }
}
