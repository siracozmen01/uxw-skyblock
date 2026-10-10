package com.uxplima.uxmskyblock.bukkit.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How many roles of their own an island's owner may make, as {@code config.conf} says under {@code roles}.
 *
 * <p>The plugin names no rank. The owner's allowance is {@code base}, raised by the number on whichever node of
 * {@code permission-tiers} they hold, the highest winning, and never past {@code max}.
 */
public record RoleConfiguration(int base, int max, Map<String, Integer> permissionTiers) {

    public RoleConfiguration {
        Objects.requireNonNull(permissionTiers, "permissionTiers must not be null");
        if (base < 0) {
            throw new IllegalArgumentException("roles.custom.base must not be negative: " + base);
        }
        if (max < base) {
            throw new IllegalArgumentException("roles.custom.max must not be below roles.custom.base: " + max);
        }
        permissionTiers = Map.copyOf(permissionTiers);
    }

    /** Three roles of their own for every owner, ten at most. */
    public static RoleConfiguration defaults() {
        return new RoleConfiguration(3, 10, Map.of());
    }

    /** Reads {@code roles.custom}. A file that leaves it out gets the defaults. */
    public static RoleConfiguration load(@Nullable ConfigurationNode rootNode) {
        if (rootNode == null) {
            return defaults();
        }
        ConfigurationNode custom = rootNode.node("roles", "custom");
        int base = Math.max(0, custom.node("base").getInt(defaults().base()));
        int max = Math.max(base, custom.node("max").getInt(defaults().max()));
        Map<String, Integer> tiers = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                custom.node("permission-tiers").childrenMap().entrySet()) {
            String node = String.valueOf(entry.getKey());
            int granted = entry.getValue().getInt(0);
            if (!node.isBlank() && granted > 0) {
                tiers.put(node, granted);
            }
        }
        return new RoleConfiguration(base, max, tiers);
    }

    /** How many roles of their own an owner holding the nodes {@code holdsPermission} accepts may make. */
    public int allowanceFor(Predicate<String> holdsPermission) {
        Objects.requireNonNull(holdsPermission, "holdsPermission must not be null");
        int allowance = base;
        for (Map.Entry<String, Integer> tier : permissionTiers.entrySet()) {
            if (holdsPermission.test(tier.getKey())) {
                allowance = Math.max(allowance, tier.getValue());
            }
        }
        return Math.min(allowance, max);
    }
}
