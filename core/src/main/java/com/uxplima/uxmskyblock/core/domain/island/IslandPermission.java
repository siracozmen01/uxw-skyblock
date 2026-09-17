package com.uxplima.uxmskyblock.core.domain.island;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Granular action permissions governing player interactions on an island.
 */
public enum IslandPermission {
    // World Interaction
    BLOCK_BREAK,
    BLOCK_PLACE,
    BUCKET_USE,
    NATURAL_INTERACT,
    REDSTONE_INTERACT,

    // Container Access
    CHEST_OPEN,
    FURNACE_USE,
    SHULKER_OPEN,
    BARREL_OPEN,
    ANVIL_USE,
    BEACON_MODIFY,

    // Spawner & Farming
    SPAWNER_BREAK,
    SPAWNER_CHANGE_TYPE,
    SPAWNER_UPGRADE,
    CROP_TRAMPLE_BYPASS,
    ANIMAL_BREED,
    ANIMAL_KILL,

    // Financial & Economy
    BANK_DEPOSIT,
    BANK_WITHDRAW,
    SHOP_ACCESS,

    // Administrative
    MEMBER_INVITE,
    MEMBER_KICK,
    MEMBER_PROMOTE,
    MEMBER_DEMOTE,
    SETTINGS_MODIFY,
    BIOME_CHANGE,
    WARP_CREATE,
    WARP_DELETE;

    public static final Set<IslandPermission> ALL_PERMISSIONS =
            Collections.unmodifiableSet(EnumSet.allOf(IslandPermission.class));
}
