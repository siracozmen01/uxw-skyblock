package com.uxplima.uxmskyblock.core.domain.template;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;

/**
 * Multi-dimension starter asset bundle mapping assets and typed creation actions (Section 2.42).
 */
public record StartTemplateBundle(
        String id,
        String displayName,
        String description,
        String requiredPermission,
        Map<DimensionId, String> dimensionAssetPaths,
        List<CreationAction> creationActions) {

    public StartTemplateBundle {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        dimensionAssetPaths = (dimensionAssetPaths != null) ? Map.copyOf(dimensionAssetPaths) : Map.of();
        creationActions = (creationActions != null) ? List.copyOf(creationActions) : List.of();
    }

    public Optional<String> assetPathFor(DimensionId dimensionId) {
        return Optional.ofNullable(dimensionAssetPaths.get(dimensionId));
    }
}
