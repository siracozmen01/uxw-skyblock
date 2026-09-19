package com.uxplima.uxmskyblock.core.domain.island;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable environmental flags map for an island.
 */
public record IslandFlags(Map<String, Boolean> values) {

    public IslandFlags {
        Objects.requireNonNull(values, "values must not be null");
        values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public static final String PVP = "PVP";
    public static final String FIRE_SPREAD = "FIRE_SPREAD";
    public static final String LEAF_DECAY = "LEAF_DECAY";
    public static final String WATER_FLOW = "WATER_FLOW";
    public static final String LAVA_FLOW = "LAVA_FLOW";
    public static final String CROP_TRAMPLE = "CROP_TRAMPLE";
    public static final String MONSTER_SPAWN = "MONSTER_SPAWN";
    public static final String ANIMAL_SPAWN = "ANIMAL_SPAWN";
    public static final String PHANTOM_SPAWN = "PHANTOM_SPAWN";
    public static final String EXPLOSION_DAMAGE = "EXPLOSION_DAMAGE";
    public static final String VISITOR_ITEM_PICKUP = "VISITOR_ITEM_PICKUP";
    public static final String VISITOR_ITEM_DROP = "VISITOR_ITEM_DROP";
    public static final String VISITOR_PVP = "VISITOR_PVP";
    public static final String VISITOR_ACCESS = "VISITOR_ACCESS";
    public static final String LOCKED = "LOCKED";

    public static IslandFlags defaults() {
        Map<String, Boolean> defaults = new HashMap<>();
        defaults.put(PVP, false);
        defaults.put(FIRE_SPREAD, false);
        defaults.put(LEAF_DECAY, true);
        defaults.put(WATER_FLOW, true);
        defaults.put(LAVA_FLOW, true);
        defaults.put(CROP_TRAMPLE, false);
        defaults.put(MONSTER_SPAWN, true);
        defaults.put(ANIMAL_SPAWN, true);
        defaults.put(PHANTOM_SPAWN, true);
        defaults.put(EXPLOSION_DAMAGE, false);
        defaults.put(VISITOR_ITEM_PICKUP, false);
        defaults.put(VISITOR_ITEM_DROP, false);
        defaults.put(VISITOR_PVP, false);
        defaults.put(VISITOR_ACCESS, true);
        defaults.put(LOCKED, false);
        return new IslandFlags(defaults);
    }

    public boolean isEnabled(String flagName) {
        Objects.requireNonNull(flagName, "flagName must not be null");
        return values.getOrDefault(flagName, false);
    }

    public IslandFlags withFlag(String flagName, boolean enabled) {
        Objects.requireNonNull(flagName, "flagName must not be null");
        Map<String, Boolean> copy = new HashMap<>(values);
        copy.put(flagName, enabled);
        return new IslandFlags(copy);
    }
}
