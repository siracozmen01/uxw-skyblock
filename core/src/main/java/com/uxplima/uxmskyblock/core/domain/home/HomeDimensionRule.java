package com.uxplima.uxmskyblock.core.domain.home;

import java.util.Objects;

/**
 * Whether a home may be set in one dimension, whether it must stand on the player's own island there,
 * and the permission node it takes. An empty node takes none.
 */
public record HomeDimensionRule(boolean allowed, boolean onIslandOnly, String permission) {

    public HomeDimensionRule {
        Objects.requireNonNull(permission, "permission must not be null");
        permission = permission.trim();
    }

    /** A dimension where anyone may set a home on their own island. */
    public static HomeDimensionRule onIsland() {
        return new HomeDimensionRule(true, true, "");
    }
}
