package com.uxplima.uxmskyblock.core.domain.biome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IslandBiomeTest {

    @ParameterizedTest
    @EnumSource(IslandBiome.class)
    @DisplayName("each biome has non-empty id, display name, and valid resource key")
    void biomesHaveValidMetadata(IslandBiome biome) {
        assertThat(biome.id()).isNotEmpty();
        assertThat(biome.displayName()).isNotEmpty();
        assertThat(biome.resourceKey()).startsWith("minecraft:");
        assertThat(biome.requiredLevel()).isNotNegative();
    }

    @Test
    @DisplayName("fromId finds biome case-insensitively and returns empty for unknown")
    void lookupFromId() {
        assertThat(IslandBiome.fromId("plains")).contains(IslandBiome.PLAINS);
        assertThat(IslandBiome.fromId("DESERT")).contains(IslandBiome.DESERT);
        assertThat(IslandBiome.fromId("nether_wastes")).contains(IslandBiome.NETHER_WASTES);
        assertThat(IslandBiome.fromId("unknown")).isEmpty();
        assertThat(IslandBiome.fromId(null)).isEmpty();
    }
}
