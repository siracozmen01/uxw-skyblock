package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class WorldConfigurationTest {

    @Test
    @DisplayName("Loads default world config when node is empty")
    void loadsDefaultWhenEmpty() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        WorldConfiguration config = WorldConfiguration.load(root);

        assertThat(config.isSuppressed("minecraft:monument")).isTrue();
        assertThat(config.isSuppressed("minecraft:mansion")).isTrue();
        assertThat(config.isSuppressed("minecraft:village")).isFalse();
    }

    @Test
    @DisplayName("Loads custom world config from HOCON")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                suppressed-structures = [
                    "minecraft:monument",
                    "minecraft:mansion"
                ]
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        WorldConfiguration config = WorldConfiguration.load(root);

        assertThat(config.isSuppressed("minecraft:monument")).isTrue();
        assertThat(config.isSuppressed("minecraft:mansion")).isTrue();
        assertThat(config.isSuppressed("minecraft:ancient_city")).isFalse();
        assertThat(config.suppressedStructures()).contains("minecraft:monument", "minecraft:mansion");
    }
}
