package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for hardware anti-lag tile and entity limits (Section 2.31).
 */
public record LimitConfiguration(boolean enabled, String bypassPermission, Map<LimitType, LimitQuota> quotas) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_BYPASS_PERMISSION = "uxmskyblock.bypass.limits";

    public LimitConfiguration {
        Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
        Objects.requireNonNull(quotas, "quotas must not be null");
        EnumMap<LimitType, LimitQuota> copy = new EnumMap<>(LimitType.class);
        copy.putAll(quotas);
        quotas = Collections.unmodifiableMap(copy);
    }

    public static LimitConfiguration defaultConfiguration() {
        Map<LimitType, LimitQuota> defaultQuotas = new EnumMap<>(LimitType.class);
        defaultQuotas.put(LimitType.HOPPER, new LimitQuota(50, new UpgradeId("HOPPER_LIMIT"), 50));
        defaultQuotas.put(LimitType.PISTON, new LimitQuota(32, new UpgradeId("PISTON_LIMIT"), 32));
        defaultQuotas.put(LimitType.STICKY_PISTON, new LimitQuota(32, new UpgradeId("PISTON_LIMIT"), 32));
        defaultQuotas.put(LimitType.OBSERVER, new LimitQuota(32, new UpgradeId("REDSTONE_LIMIT"), 32));
        defaultQuotas.put(LimitType.DROPPER, new LimitQuota(32, new UpgradeId("REDSTONE_LIMIT"), 32));
        defaultQuotas.put(LimitType.DISPENSER, new LimitQuota(32, new UpgradeId("REDSTONE_LIMIT"), 32));
        defaultQuotas.put(LimitType.BREWING_STAND, new LimitQuota(16, new UpgradeId("BREWING_LIMIT"), 16));
        defaultQuotas.put(LimitType.SPAWNER, new LimitQuota(25, new UpgradeId("SPAWNER_LIMIT"), 25));
        defaultQuotas.put(LimitType.VILLAGER, new LimitQuota(20, new UpgradeId("VILLAGER_LIMIT"), 10));
        defaultQuotas.put(LimitType.ARMOR_STAND, new LimitQuota(30, new UpgradeId("ENTITY_LIMIT"), 20));
        defaultQuotas.put(LimitType.MINECART, new LimitQuota(16, new UpgradeId("ENTITY_LIMIT"), 16));
        defaultQuotas.put(LimitType.BOAT, new LimitQuota(16, new UpgradeId("ENTITY_LIMIT"), 16));

        return new LimitConfiguration(DEFAULT_ENABLED, DEFAULT_BYPASS_PERMISSION, defaultQuotas);
    }

    public static LimitConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("limits");
        if (node.virtual()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        String bypassPermission = node.node("bypass-permission").getString(DEFAULT_BYPASS_PERMISSION);

        ConfigurationNode quotasNode = node.node("quotas");
        if (quotasNode.virtual() || quotasNode.childrenMap().isEmpty()) {
            return new LimitConfiguration(
                    enabled, bypassPermission, defaultConfiguration().quotas());
        }

        Map<LimitType, LimitQuota> loadedQuotas = new EnumMap<>(LimitType.class);
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                quotasNode.childrenMap().entrySet()) {
            String keyStr = String.valueOf(entry.getKey()).toUpperCase(Locale.ROOT);
            LimitType type;
            try {
                type = LimitType.valueOf(keyStr);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            ConfigurationNode qNode = entry.getValue();
            int base = qNode.node("base").getInt(50);
            String upgradeKey = qNode.node("upgrade-id").getString("");
            UpgradeId upgradeId = (upgradeKey != null && !upgradeKey.isBlank()) ? new UpgradeId(upgradeKey) : null;
            int perTier = qNode.node("per-tier").getInt(0);

            loadedQuotas.put(type, new LimitQuota(base, upgradeId, perTier));
        }

        // Fill missing defaults
        for (Map.Entry<LimitType, LimitQuota> defaultEntry :
                defaultConfiguration().quotas().entrySet()) {
            loadedQuotas.putIfAbsent(defaultEntry.getKey(), defaultEntry.getValue());
        }

        return new LimitConfiguration(enabled, bypassPermission, loadedQuotas);
    }
}
