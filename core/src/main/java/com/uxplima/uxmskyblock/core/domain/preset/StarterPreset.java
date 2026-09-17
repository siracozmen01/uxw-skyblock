package com.uxplima.uxmskyblock.core.domain.preset;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;

/**
 * Pure domain value record representing an island starter preset configuration.
 */
public record StarterPreset(
        String id, String displayName, String description, String schematicPath, IslandBiome defaultBiome) {

    public StarterPreset {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(schematicPath, "schematicPath must not be null");
        Objects.requireNonNull(defaultBiome, "defaultBiome must not be null");
    }
}
