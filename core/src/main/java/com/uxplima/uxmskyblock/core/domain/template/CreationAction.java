package com.uxplima.uxmskyblock.core.domain.template;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;

/**
 * Typed creation action executed during root/instance initialization.
 */
public sealed interface CreationAction {

    record PasteSchematic(
            DimensionId dimensionId,
            String schematicAssetPath,
            int relativeX,
            int relativeY,
            int relativeZ)
            implements CreationAction {
        public PasteSchematic {
            Objects.requireNonNull(dimensionId, "dimensionId must not be null");
            Objects.requireNonNull(schematicAssetPath, "schematicAssetPath must not be null");
        }
    }

    record SetSpawn(
            DimensionId dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch)
            implements CreationAction {
        public SetSpawn {
            Objects.requireNonNull(dimensionId, "dimensionId must not be null");
        }
    }

    record GeneratePlatform(
            DimensionId dimensionId,
            String materialName,
            int radius)
            implements CreationAction {
        public GeneratePlatform {
            Objects.requireNonNull(dimensionId, "dimensionId must not be null");
            Objects.requireNonNull(materialName, "materialName must not be null");
        }
    }
}
