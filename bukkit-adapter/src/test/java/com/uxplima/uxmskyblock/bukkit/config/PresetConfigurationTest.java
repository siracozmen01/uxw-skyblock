package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;

import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The starter islands are the operator's list, not ours.
 *
 * <p>The four the plugin shipped with were written in Java, with their names, their descriptions and
 * their schematic paths, and a server that built its own starter island had no way to offer it. A
 * fifth preset meant a release.
 */
class PresetConfigurationTest {

    private static CommentedConfigurationNode parse(String hocon) throws Exception {
        return HoconConfigurationLoader.builder()
                .source(() -> new java.io.BufferedReader(new StringReader(hocon)))
                .build()
                .load();
    }

    @Test
    @DisplayName("A preset an operator adds is a preset a player can start with")
    void anOperatorCanAddOne() throws Exception {
        CommentedConfigurationNode root = parse("""
                presets {
                    default = "sandstone"
                    entries {
                        sandstone {
                            display-name = "@presets.sandstone.name"
                            description = "@presets.sandstone.description"
                            schematic = "schematics/sandstone.schem"
                            biome = "DESERT"
                        }
                        classic {
                            display-name = "@presets.classic.name"
                            description = "@presets.classic.description"
                            schematic = "schematics/classic.schem"
                            biome = "PLAINS"
                        }
                    }
                }
                """);

        StarterPresetCatalog catalogue = PresetConfiguration.load(root).catalogue();

        assertThat(catalogue.findById("sandstone")).isPresent();
        assertThat(catalogue.findById("sandstone").orElseThrow().schematicPath())
                .isEqualTo("schematics/sandstone.schem");
        assertThat(catalogue.findById("sandstone").orElseThrow().defaultBiome()).isEqualTo(IslandBiome.DESERT);
        assertThat(catalogue.defaultPreset().id()).isEqualTo("sandstone");
        assertThat(catalogue.allPresets()).hasSize(2);
    }

    @Test
    @DisplayName("A preset an operator removes is one no player can start with")
    void anOperatorCanRemoveOne() throws Exception {
        CommentedConfigurationNode root = parse("""
                presets {
                    default = "classic"
                    entries {
                        classic {
                            schematic = "schematics/classic.schem"
                            biome = "PLAINS"
                        }
                    }
                }
                """);

        StarterPresetCatalog catalogue = PresetConfiguration.load(root).catalogue();

        assertThat(catalogue.allPresets()).hasSize(1);
        assertThat(catalogue.findById("nether")).isEmpty();
    }

    @Test
    @DisplayName("A file that names no preset leaves the server with the ones it ships")
    void anEmptyFileKeepsTheShippedOnes() throws Exception {
        StarterPresetCatalog catalogue =
                PresetConfiguration.load(parse("other = 1")).catalogue();

        assertThat(catalogue.allPresets())
                .extracting(preset -> preset.id())
                .containsExactlyInAnyOrder("classic", "desert", "nether", "cave");
    }

    @Test
    @DisplayName("A default naming no preset falls to the first rather than leaving none")
    void anUnknownDefaultFallsToTheFirst() throws Exception {
        CommentedConfigurationNode root = parse("""
                presets {
                    default = "typo"
                    entries {
                        cave { schematic = "schematics/cave.schem" }
                    }
                }
                """);

        assertThat(PresetConfiguration.load(root).catalogue().defaultPreset().id())
                .isEqualTo("cave");
    }

    @Test
    @DisplayName("A biome the server does not know is plains rather than a start that fails")
    void anUnknownBiomeIsPlains() throws Exception {
        CommentedConfigurationNode root = parse("""
                presets {
                    entries {
                        odd { biome = "NOT_A_BIOME" }
                    }
                }
                """);

        assertThat(PresetConfiguration.load(root)
                        .catalogue()
                        .findById("odd")
                        .orElseThrow()
                        .defaultBiome())
                .isEqualTo(IslandBiome.PLAINS);
    }

    @Test
    @DisplayName("A preset's dimensions are read by id, a server's own among them, each with its height")
    void dimensionsAreReadById() throws Exception {
        CommentedConfigurationNode root = parse("""
                presets {
                    entries {
                        realm {
                            dimensions {
                                the_nether { start = ["uxm:dimension-platform"], height = 70 }
                                "myserver:mining_realm" { start = ["myserver:shaft"], height = 12 }
                                the_end { start = [] }
                            }
                        }
                        plain { }
                    }
                }
                """);
        PresetConfiguration config = PresetConfiguration.load(root);
        var realm = config.catalogue().findById("realm").orElseThrow().dimensions();

        assertThat(realm.resolve(com.uxplima.uxmskyblock.core.domain.dimension.DimensionId.THE_NETHER))
                .get()
                .extracting(com.uxplima.uxmskyblock.core.domain.preset.StartTemplate::height)
                .isEqualTo(70);
        assertThat(realm.resolve(com.uxplima.uxmskyblock.core.domain.dimension.DimensionId.of("myserver:mining_realm")))
                .get()
                .extracting(com.uxplima.uxmskyblock.core.domain.preset.StartTemplate::actions)
                .isEqualTo(java.util.List.of("myserver:shaft"));
        assertThat(realm.resolve(com.uxplima.uxmskyblock.core.domain.dimension.DimensionId.THE_END))
                .describedAs("a dimension with nothing to build is left out")
                .isEmpty();
        assertThat(config.catalogue().findById("plain").orElseThrow().dimensions())
                .isEqualTo(com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle.shipped());

        var offered = config.startableWith(actions -> !actions.contains("myserver:shaft"))
                .catalogue();
        assertThat(offered.findById("realm"))
                .describedAs("a dimension action nobody provides takes the preset out of the offer")
                .isEmpty();
        assertThat(offered.findById("plain")).isPresent();
    }

    @Test
    @DisplayName("No sentence a player reads about a preset is written in Java")
    void everyShippedPresetNamesACatalogueKey() {
        for (var preset : StarterPresetCatalog.shipped()) {
            assertThat(preset.displayName())
                    .describedAs("the display name of %s", preset.id())
                    .startsWith("@");
            assertThat(preset.description())
                    .describedAs("the description of %s", preset.id())
                    .startsWith("@");
        }
    }
}
