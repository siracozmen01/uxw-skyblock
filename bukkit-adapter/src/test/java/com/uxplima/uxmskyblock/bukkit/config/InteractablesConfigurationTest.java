package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.bukkit.Material;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class InteractablesConfigurationTest {

    @Test
    @DisplayName("Loads default interactables config when node is empty")
    void loadsDefaultWhenEmpty() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        InteractablesConfiguration config = InteractablesConfiguration.load(root);

        assertThat(config.resolvePermission(Material.OAK_DOOR))
                .contains(InteractablesConfiguration.PERM_DOORS_AND_GATES);

        assertThat(config.resolvePermission(Material.LEVER))
                .contains(InteractablesConfiguration.PERM_REDSTONE_TRIGGERS);

        assertThat(config.resolvePermission(Material.CHEST)).contains(InteractablesConfiguration.PERM_CONTAINERS);

        assertThat(config.resolvePermission(Material.CRAFTING_TABLE))
                .contains(InteractablesConfiguration.PERM_WORKSTATIONS);

        assertThat(config.resolvePermission(Material.DIRT)).isEmpty();
    }

    @Test
    @DisplayName("Loads custom interactables config from HOCON")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                doors = ["IRON_DOOR"]
                redstone = ["STONE_BUTTON"]
                containers = ["BARREL"]
                workstations = ["ANVIL"]
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        InteractablesConfiguration config = InteractablesConfiguration.load(root);

        assertThat(config.resolvePermission(Material.IRON_DOOR))
                .contains(InteractablesConfiguration.PERM_DOORS_AND_GATES);
        assertThat(config.resolvePermission(Material.OAK_DOOR)).isEmpty();

        assertThat(config.resolvePermission(Material.STONE_BUTTON))
                .contains(InteractablesConfiguration.PERM_REDSTONE_TRIGGERS);

        assertThat(config.resolvePermission(Material.BARREL)).contains(InteractablesConfiguration.PERM_CONTAINERS);

        assertThat(config.resolvePermission(Material.ANVIL)).contains(InteractablesConfiguration.PERM_WORKSTATIONS);
    }
}
