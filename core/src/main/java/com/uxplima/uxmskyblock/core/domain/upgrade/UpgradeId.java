package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The key of one upgrade, which is the name of its section in {@code modules/upgrades.conf}.
 *
 * <p>The constants here name the upgrades the plugin itself reads, and each one holds the key the
 * operator's file uses. They used to hold short names of their own, {@code SIZE} where the file said
 * {@code island_size}, so the island control menu asked for a tier nothing had ever stored and every
 * island showed tier 0 whatever it had bought. One key, one upgrade, one file.
 */
public record UpgradeId(String key) {

    /** How big the island is, in blocks from its centre. */
    public static final UpgradeId SIZE = UpgradeId.of("island_size");

    /** How many members the island may hold. */
    public static final UpgradeId MEMBERS = UpgradeId.of("member_limit");

    /** How many warps the island may publish. */
    public static final UpgradeId WARPS = UpgradeId.of("warp_slots");

    /** How fast crops grow on the island. */
    public static final UpgradeId CROP_GROWTH = UpgradeId.of("crop_growth");

    /** How fast the island's spawners run. */
    public static final UpgradeId SPAWNER_RATES = UpgradeId.of("spawner_rates");

    /** Which ores the island's generator produces. */
    public static final UpgradeId ORE_GENERATOR = UpgradeId.of("ore_generator");

    /** How many pages the island's shared vault has. */
    public static final UpgradeId VAULT_PAGES = UpgradeId.of("vault_pages");

    /** Every upgrade the plugin itself reads a value from. */
    public static List<UpgradeId> builtIn() {
        return List.of(SIZE, MEMBERS, WARPS, CROP_GROWTH, SPAWNER_RATES, ORE_GENERATOR, VAULT_PAGES);
    }

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
