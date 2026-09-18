package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.Locale;
import java.util.Objects;

/**
 * Value object representing an upgrade category key (e.g. SIZE, MEMBERS, CROP_GROWTH).
 */
public record UpgradeId(String key) {

    public static final UpgradeId SIZE = UpgradeId.of("SIZE");
    public static final UpgradeId MEMBERS = UpgradeId.of("MEMBERS");
    public static final UpgradeId WARPS = UpgradeId.of("WARPS");
    public static final UpgradeId CROP_GROWTH = UpgradeId.of("CROP_GROWTH");
    public static final UpgradeId SPAWNER_SPEED = UpgradeId.of("SPAWNER_SPEED");
    public static final UpgradeId ORE_GENERATOR = UpgradeId.of("ORE_GENERATOR");
    public static final UpgradeId VAULT_PAGES = UpgradeId.of("VAULT_PAGES");

    public UpgradeId {
        Objects.requireNonNull(key, "key");
        key = key.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Upgrade key cannot be empty");
        }
    }

    public static UpgradeId of(String key) {
        return new UpgradeId(key);
    }
}
