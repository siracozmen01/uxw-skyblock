package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for temporary access grants subsystem (ACC-001).
 */
public record TemporaryAccessConfiguration(
        boolean enabled,
        Duration defaultDuration,
        Duration maxDuration,
        Duration purgeInterval,
        boolean enforceRulesetIsolation,
        Duration grantLookupTtl,
        java.util.Set<PermissionKey> trustPermissions) {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(TemporaryAccessConfiguration.class.getName());

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_DURATION = Duration.ofHours(1);
    public static final Duration DEFAULT_MAX_DURATION = Duration.ofDays(1);
    public static final Duration DEFAULT_PURGE_INTERVAL = Duration.ofMinutes(1);
    public static final boolean DEFAULT_ENFORCE_RULESET_ISOLATION = true;
    public static final Duration DEFAULT_GRANT_LOOKUP_TTL =
            com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService.DEFAULT_GRANT_LOOKUP_TTL;

    /**
     * What a trust grant lets the trusted player do, when the operator names nothing.
     *
     * <p>Building, breaking and the everyday containers: the things a guest helping out needs. It
     * deliberately carries nothing economic, nothing that changes the island's settings and nothing
     * that touches its membership, because a trusted visitor is never a member.
     */
    public static final java.util.Set<PermissionKey> DEFAULT_TRUST_PERMISSIONS = java.util.Set.of(
            StandardPermissions.BLOCK_BREAK,
            StandardPermissions.BLOCK_PLACE,
            StandardPermissions.BUCKET_USE,
            StandardPermissions.NATURAL_INTERACT,
            StandardPermissions.REDSTONE_INTERACT,
            StandardPermissions.CHEST_OPEN,
            StandardPermissions.FURNACE_USE,
            StandardPermissions.BARREL_OPEN,
            StandardPermissions.ANVIL_USE,
            StandardPermissions.CROP_TRAMPLE_BYPASS,
            StandardPermissions.ANIMAL_BREED);

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
        Objects.requireNonNull(grantLookupTtl, "grantLookupTtl must not be null");
        if (grantLookupTtl.isNegative()) {
            throw new IllegalArgumentException("grantLookupTtl must not be negative: " + grantLookupTtl);
        }
        Objects.requireNonNull(trustPermissions, "trustPermissions must not be null");
        trustPermissions = java.util.Set.copyOf(trustPermissions);
    }

    public static TemporaryAccessConfiguration defaultConfiguration() {
        return new TemporaryAccessConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_DURATION,
                DEFAULT_MAX_DURATION,
                DEFAULT_PURGE_INTERVAL,
                DEFAULT_ENFORCE_RULESET_ISOLATION,
                DEFAULT_GRANT_LOOKUP_TTL,
                DEFAULT_TRUST_PERMISSIONS);
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

        String ttlRaw = node.node("grant-lookup-ttl").getString();
        Duration grantLookupTtl =
                ttlRaw != null && !ttlRaw.isBlank() ? Durations.parse(ttlRaw) : DEFAULT_GRANT_LOOKUP_TTL;

        java.util.Set<PermissionKey> trustPermissions = readTrustPermissions(node.node("trust-permissions"));

        return new TemporaryAccessConfiguration(
                enabled,
                defaultDuration,
                maxDuration,
                purgeInterval,
                enforceIsolation,
                grantLookupTtl,
                trustPermissions);
    }

    /**
     * Reads what a trust grant carries.
     *
     * <p>A key the operator mistyped is skipped and the rest are kept: a server that will not start
     * over one line in a permission list is a worse outcome than one that starts with the rest. An
     * empty or missing list is the shipped set, because a grant that lets the guest do nothing is
     * indistinguishable from the feature being broken.
     */
    private static java.util.Set<PermissionKey> readTrustPermissions(ConfigurationNode node) {
        if (node.virtual() || node.empty()) {
            return DEFAULT_TRUST_PERMISSIONS;
        }
        java.util.Set<PermissionKey> keys = new java.util.LinkedHashSet<>();
        for (ConfigurationNode child : node.childrenList()) {
            String raw = child.getString();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                keys.add(PermissionKey.of(raw.strip()));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "temporary-access.trust-permissions names \"" + raw
                        + "\", which is not a permission key. It is skipped.");
            }
        }
        return keys.isEmpty() ? DEFAULT_TRUST_PERMISSIONS : java.util.Set.copyOf(keys);
    }
}
