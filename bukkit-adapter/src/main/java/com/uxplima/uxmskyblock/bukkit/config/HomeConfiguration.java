package com.uxplima.uxmskyblock.bukkit.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.home.HomeLimitPolicy;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How many named homes a player may keep, and which permission buys more.
 *
 * <p>The tiers are a map the operator writes: a permission node and the number of homes it grants.
 * No rank is named in the code, so a server with three ranks and a server with nine are the same
 * plugin. The base is what everybody gets and the highest node a player holds wins.
 */
public record HomeConfiguration(boolean enabled, int baseHomes, int maxHomes, Map<String, Integer> permissionTiers) {

    public HomeConfiguration {
        Objects.requireNonNull(permissionTiers, "permissionTiers must not be null");
        if (baseHomes < 1) {
            throw new IllegalArgumentException("homes.base must be at least 1: " + baseHomes);
        }
        if (maxHomes < baseHomes) {
            throw new IllegalArgumentException("homes.max must not be below homes.base: " + maxHomes);
        }
        permissionTiers = Map.copyOf(permissionTiers);
    }

    public static HomeConfiguration defaults() {
        return new HomeConfiguration(true, 1, 10, Map.of());
    }

    public static HomeConfiguration load(@Nullable ConfigurationNode rootNode) {
        if (rootNode == null) {
            return defaults();
        }
        ConfigurationNode homes = rootNode.node("homes");
        boolean enabled = homes.node("enabled").getBoolean(true);
        int base = homes.node("base").getInt(1);
        int max = homes.node("max").getInt(10);

        Map<String, Integer> tiers = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                homes.node("permission-tiers").childrenMap().entrySet()) {
            String node = String.valueOf(entry.getKey());
            int granted = entry.getValue().getInt(0);
            if (!node.isBlank() && granted > 0) {
                tiers.put(node, granted);
            }
        }
        return new HomeConfiguration(enabled, Math.max(1, base), Math.max(Math.max(1, base), max), tiers);
    }

    /**
     * The allowance a player has, given the nodes they hold. The engine asks for a tier number, so
     * this hands it the allowance directly and the policy passes it through: the ladder is the
     * operator's file, not an arithmetic rule in the code.
     */
    public int allowanceFor(java.util.function.Predicate<String> holdsPermission) {
        Objects.requireNonNull(holdsPermission, "holdsPermission must not be null");
        int allowance = baseHomes;
        for (Map.Entry<String, Integer> tier : permissionTiers.entrySet()) {
            if (holdsPermission.test(tier.getKey())) {
                allowance = Math.max(allowance, tier.getValue());
            }
        }
        return Math.min(allowance, maxHomes);
    }

    /** The policy the service reads. The caller has already turned nodes into an allowance. */
    public HomeLimitPolicy limitPolicy() {
        int ceiling = maxHomes;
        int floor = baseHomes;
        return allowance -> Math.min(ceiling, Math.max(floor, allowance));
    }
}
