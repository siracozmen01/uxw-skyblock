package com.uxplima.uxmskyblock.core.application.preset;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StarterPresetCatalogTest {

    private StarterPresetCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new StarterPresetCatalog();
    }

    @Test
    @DisplayName("catalog contains all 4 standard starter presets")
    void containsStandardPresets() {
        assertThat(catalog.allPresets()).hasSize(4);
        assertThat(catalog.findById("classic")).contains(StarterPresetCatalog.CLASSIC);
        assertThat(catalog.findById("desert")).contains(StarterPresetCatalog.DESERT);
        assertThat(catalog.findById("nether")).contains(StarterPresetCatalog.NETHER);
        assertThat(catalog.findById("cave")).contains(StarterPresetCatalog.CAVE);
    }

    @Test
    @DisplayName("default preset is CLASSIC")
    void defaultPresetIsClassic() {
        assertThat(catalog.defaultPreset()).isEqualTo(StarterPresetCatalog.CLASSIC);
    }

    @Test
    @DisplayName("case-insensitive lookup with trimming")
    void caseInsensitiveLookup() {
        assertThat(catalog.findById("  DeSeRt  ")).contains(StarterPresetCatalog.DESERT);
        assertThat(catalog.findById("nonexistent")).isEmpty();
        assertThat(catalog.findById(null)).isEmpty();
    }

    @Test
    @DisplayName("all presets have valid schematic paths and biomes")
    void presetValidation() {
        for (StarterPreset preset : catalog.allPresets()) {
            assertThat(preset.id()).isNotEmpty();
            assertThat(preset.displayName()).isNotEmpty();
            assertThat(preset.description()).isNotEmpty();
            assertThat(preset.schematicPath()).startsWith("schematics/").endsWith(".schem");
            assertThat(preset.defaultBiome()).isNotNull();
        }
    }
}
