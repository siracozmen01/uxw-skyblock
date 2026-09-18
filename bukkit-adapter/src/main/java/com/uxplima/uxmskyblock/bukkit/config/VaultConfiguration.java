package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island vault paged storage, lease timeouts, and audit logging parameters.
 */
public record VaultConfiguration(
        boolean enabled, int basePages, int maxPages, int slotsPerPage, Duration leaseDuration, int auditLogLimit) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_BASE_PAGES = 1;
    public static final int DEFAULT_MAX_PAGES = 10;
    public static final int DEFAULT_SLOTS_PER_PAGE = 54;
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(60);
    public static final int DEFAULT_AUDIT_LOG_LIMIT = 50;

    public VaultConfiguration {
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (basePages < 1) {
            throw new IllegalArgumentException("basePages must be >= 1: " + basePages);
        }
        if (maxPages < basePages) {
            throw new IllegalArgumentException(
                    "maxPages (" + maxPages + ") cannot be less than basePages (" + basePages + ")");
        }
        if (slotsPerPage < 9 || slotsPerPage > 54 || slotsPerPage % 9 != 0) {
            throw new IllegalArgumentException(
                    "slotsPerPage must be a valid container size multiple of 9 between 9 and 54: " + slotsPerPage);
        }
        if (auditLogLimit < 1) {
            throw new IllegalArgumentException("auditLogLimit must be >= 1: " + auditLogLimit);
        }
    }

    public static VaultConfiguration defaultConfiguration() {
        return new VaultConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_BASE_PAGES,
                DEFAULT_MAX_PAGES,
                DEFAULT_SLOTS_PER_PAGE,
                DEFAULT_LEASE_DURATION,
                DEFAULT_AUDIT_LOG_LIMIT);
    }

    public static VaultConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("vault");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        int basePages = node.node("base-pages").getInt(DEFAULT_BASE_PAGES);
        int maxPages = node.node("max-pages").getInt(DEFAULT_MAX_PAGES);
        int slotsPerPage = node.node("slots-per-page").getInt(DEFAULT_SLOTS_PER_PAGE);

        String leaseRaw = node.node("lease-duration-seconds").getString();
        Duration leaseDuration = DEFAULT_LEASE_DURATION;
        if (leaseRaw != null && !leaseRaw.isBlank()) {
            leaseDuration = leaseRaw.matches("^\\d+$")
                    ? Duration.ofSeconds(Long.parseLong(leaseRaw))
                    : Durations.parse(leaseRaw);
        }

        int auditLogLimit = node.node("audit-log-limit").getInt(DEFAULT_AUDIT_LOG_LIMIT);

        return new VaultConfiguration(enabled, basePages, maxPages, slotsPerPage, leaseDuration, auditLogLimit);
    }
}
