package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Categorization for community warp explorer directory.
 */
public enum WarpCategory {
    GENERAL("General", "OAK_SIGN"),
    SHOPS("Shops & Markets", "CHEST"),
    FARMS("Automated Farms", "GOLDEN_HOE"),
    PARKOUR("Parkour & Mini-Games", "LEATHER_BOOTS"),
    SHOWCASES("Island Showcases", "DIAMOND_BLOCK");

    private final String displayName;
    private final String defaultIconMaterial;

    WarpCategory(String displayName, String defaultIconMaterial) {
        this.displayName = displayName;
        this.defaultIconMaterial = defaultIconMaterial;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultIconMaterial() {
        return defaultIconMaterial;
    }

    public static WarpCategory parseCategory(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        try {
            return WarpCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }
}
