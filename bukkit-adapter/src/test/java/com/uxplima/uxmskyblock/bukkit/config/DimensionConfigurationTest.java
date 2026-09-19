package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMode;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class DimensionConfigurationTest {

    @Test
    @DisplayName("Loads default configuration when node is missing")
    void loadsDefaultWhenMissing() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        DimensionConfiguration config = DimensionConfiguration.load(root);

        assertThat(config.enabled()).isTrue();
        assertThat(config.mappings())
                .containsKeys(IslandDimensionType.OVERWORLD, IslandDimensionType.NETHER, IslandDimensionType.THE_END);

        DimensionMapping nether =
                java.util.Objects.requireNonNull(config.mappings().get(IslandDimensionType.NETHER));
        assertThat(nether.mode()).isEqualTo(DimensionMode.PRIVATE_ISLAND);
        assertThat(nether.worldName()).isEqualTo("skyblock_nether");
        assertThat(nether.requiredUpgrade()).isEqualTo(new UpgradeId("island_nether"));
        assertThat(nether.schematic()).isEqualTo("island_nether");

        DimensionMapping end =
                java.util.Objects.requireNonNull(config.mappings().get(IslandDimensionType.THE_END));
        assertThat(end.mode()).isEqualTo(DimensionMode.PRIVATE_ISLAND);
        assertThat(end.worldName()).isEqualTo("skyblock_the_end");
        assertThat(end.requiredUpgrade()).isEqualTo(new UpgradeId("island_end"));
        assertThat(end.schematic()).isEqualTo("island_end");
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON string")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                dimensions {
                    enabled = true
                    nether {
                        mode = "SHARED_WORLD"
                        world-name = "custom_nether"
                        required-upgrade = ""
                        schematic = ""
                    }
                    end {
                        mode = "DISABLED"
                        world-name = "custom_end"
                        required-upgrade = "island_end_custom"
                        schematic = "end_custom"
                    }
                }
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        DimensionConfiguration config = DimensionConfiguration.load(root);

        assertThat(config.enabled()).isTrue();

        DimensionMapping nether =
                java.util.Objects.requireNonNull(config.mappings().get(IslandDimensionType.NETHER));
        assertThat(nether.mode()).isEqualTo(DimensionMode.SHARED_WORLD);
        assertThat(nether.worldName()).isEqualTo("custom_nether");
        assertThat(nether.requiredUpgrade()).isNull();

        DimensionMapping end =
                java.util.Objects.requireNonNull(config.mappings().get(IslandDimensionType.THE_END));
        assertThat(end.mode()).isEqualTo(DimensionMode.DISABLED);
        assertThat(end.worldName()).isEqualTo("custom_end");
        assertThat(end.requiredUpgrade()).isEqualTo(new UpgradeId("island_end_custom"));
    }
}
