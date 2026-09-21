package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration record for anti-alt and starter economy protection (Section 2.32).
 */
public record AntiAbuseConfiguration(
        boolean purgeInventoryOnReset,
        Duration quarantineDuration,
        Duration resetCooldown,
        int maxResetsPerDay,
        Duration resetWindowDuration,
        Duration coopJoinCooldown,
        Duration quarantineLookupTtl,
        String resetBypassPermission,
        String coopBypassPermission,
        String quarantineBypassPermission) {

    public static final boolean DEFAULT_PURGE_INVENTORY_ON_RESET = true;
    public static final Duration DEFAULT_QUARANTINE_DURATION = Duration.ofMinutes(15);
    public static final Duration DEFAULT_RESET_COOLDOWN = Duration.ofHours(12);
    public static final int DEFAULT_MAX_RESETS_PER_DAY = 3;
    public static final Duration DEFAULT_RESET_WINDOW_DURATION = Duration.ofHours(24);
    public static final Duration DEFAULT_COOP_JOIN_COOLDOWN = Duration.ofHours(24);

    /**
     * How long this node trusts its own answer that an island is not quarantined.
     *
     * <p>Movement is checked against the quarantine list, and every player sends many movement
     * packets a second. This node knows every quarantine it set itself; the lookup exists to notice
     * one another node set, and it is worth at most one query this often, never one per step.
     */
    public static final Duration DEFAULT_QUARANTINE_LOOKUP_TTL = Duration.ofSeconds(30);

    public static final String DEFAULT_RESET_BYPASS_PERMISSION = "uxmskyblock.bypass.resetlimits";
    public static final String DEFAULT_COOP_BYPASS_PERMISSION = "uxmskyblock.bypass.coopcooldown";
    public static final String DEFAULT_QUARANTINE_BYPASS_PERMISSION = "uxmskyblock.bypass.quarantine";

    public AntiAbuseConfiguration {
        Objects.requireNonNull(quarantineDuration, "quarantineDuration must not be null");
        Objects.requireNonNull(resetCooldown, "resetCooldown must not be null");
        Objects.requireNonNull(resetWindowDuration, "resetWindowDuration must not be null");
        Objects.requireNonNull(coopJoinCooldown, "coopJoinCooldown must not be null");
        Objects.requireNonNull(quarantineLookupTtl, "quarantineLookupTtl must not be null");
        Objects.requireNonNull(resetBypassPermission, "resetBypassPermission must not be null");
        Objects.requireNonNull(coopBypassPermission, "coopBypassPermission must not be null");
        Objects.requireNonNull(quarantineBypassPermission, "quarantineBypassPermission must not be null");
    }

    public static AntiAbuseConfiguration defaultConfiguration() {
        return new AntiAbuseConfiguration(
                DEFAULT_PURGE_INVENTORY_ON_RESET,
                DEFAULT_QUARANTINE_DURATION,
                DEFAULT_RESET_COOLDOWN,
                DEFAULT_MAX_RESETS_PER_DAY,
                DEFAULT_RESET_WINDOW_DURATION,
                DEFAULT_COOP_JOIN_COOLDOWN,
                DEFAULT_QUARANTINE_LOOKUP_TTL,
                DEFAULT_RESET_BYPASS_PERMISSION,
                DEFAULT_COOP_BYPASS_PERMISSION,
                DEFAULT_QUARANTINE_BYPASS_PERMISSION);
    }

    public static AntiAbuseConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("anti-abuse");
        if (node.virtual()) {
            return defaultConfiguration();
        }

        boolean purgeOnReset = node.node("purge-inventory-on-reset").getBoolean(DEFAULT_PURGE_INVENTORY_ON_RESET);
        Duration quarantineDuration = parseDuration(node.node("quarantine-duration"), DEFAULT_QUARANTINE_DURATION);
        Duration resetCooldown = parseDuration(node.node("reset-cooldown"), DEFAULT_RESET_COOLDOWN);
        int maxResetsPerDay = node.node("max-resets-per-day").getInt(DEFAULT_MAX_RESETS_PER_DAY);
        Duration resetWindowDuration = parseDuration(node.node("reset-window-duration"), DEFAULT_RESET_WINDOW_DURATION);
        Duration coopJoinCooldown = parseDuration(node.node("coop-join-cooldown"), DEFAULT_COOP_JOIN_COOLDOWN);
        Duration quarantineLookupTtl = parseDuration(node.node("quarantine-lookup-ttl"), DEFAULT_QUARANTINE_LOOKUP_TTL);
        String resetBypass = node.node("reset-bypass-permission").getString(DEFAULT_RESET_BYPASS_PERMISSION);
        String coopBypass = node.node("coop-bypass-permission").getString(DEFAULT_COOP_BYPASS_PERMISSION);
        String quarantineBypass =
                node.node("quarantine-bypass-permission").getString(DEFAULT_QUARANTINE_BYPASS_PERMISSION);

        return new AntiAbuseConfiguration(
                purgeOnReset,
                quarantineDuration,
                resetCooldown,
                maxResetsPerDay,
                resetWindowDuration,
                coopJoinCooldown,
                quarantineLookupTtl,
                resetBypass,
                coopBypass,
                quarantineBypass);
    }

    private static Duration parseDuration(ConfigurationNode node, Duration fallback) {
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        raw = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (raw.endsWith("d")) {
                long days = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofDays(days);
            } else if (raw.endsWith("h")) {
                long hours = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofHours(hours);
            } else if (raw.endsWith("m")) {
                long minutes = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofMinutes(minutes);
            } else if (raw.endsWith("s")) {
                long seconds = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofSeconds(seconds);
            }
            return Duration.ofSeconds(Long.parseLong(raw));
        } catch (Exception e) {
            return fallback;
        }
    }
}
