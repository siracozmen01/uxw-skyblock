package com.uxplima.uxmskyblock.core.domain.webmap;

import java.util.Objects;

/**
 * Domain model representing a rendered web map polygon marker for Dynmap, BlueMap, or Pl3xMap (Section 2.42).
 */
public record WebMapMarker(
        String markerId,
        String islandId,
        String displayName,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String borderColorHex,
        String fillColorHex,
        double fillOpacity,
        int lineWeight,
        String htmlTooltip) {

    public WebMapMarker {
        Objects.requireNonNull(markerId, "markerId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(borderColorHex, "borderColorHex must not be null");
        Objects.requireNonNull(fillColorHex, "fillColorHex must not be null");
        Objects.requireNonNull(htmlTooltip, "htmlTooltip must not be null");
    }
}
