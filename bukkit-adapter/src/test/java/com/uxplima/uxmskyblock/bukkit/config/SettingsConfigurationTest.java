package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class SettingsConfigurationTest {

    @Test
    @DisplayName("Loads default settings when node is empty")
    void loadsDefaultWhenEmpty() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        SettingsConfiguration config = SettingsConfiguration.load(root);

        assertThat(config.obsidianToLava()).isTrue();
        assertThat(config.voidTeleportMembers()).isTrue();
        assertThat(config.voidTeleportVisitors()).isTrue();
        assertThat(config.defaultCommandAction()).isEqualTo("auto");
        assertThat(config.islandNamesEnabled()).isTrue();
        assertThat(config.islandNamesMinLength()).isEqualTo(3);
        assertThat(config.islandNamesMaxLength()).isEqualTo(16);
        assertThat(config.teleportWarmup()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.teleportOnPvpEnable()).isTrue();
        assertThat(config.immuneToPvpWhenTeleport()).isTrue();
        assertThat(config.pvpTeleportInvulnerability()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.stopBorderCrossing()).isTrue();
        assertThat(config.disableRedstoneOffline()).isTrue();
        assertThat(config.afkDisableSpawning()).isTrue();
        assertThat(config.afkDisableRedstone()).isTrue();
        assertThat(config.netherRoof()).isFalse();
        assertThat(config.syncWorthWithShop()).isEqualTo("BUY");
        assertThat(config.negativeLevelAllowed()).isFalse();
        assertThat(config.endDragonFightEnabled()).isTrue();
    }

    @Test
    @DisplayName("Loads custom configuration from HOCON")
    void loadsCustomHocon() throws IOException {
        String hocon = """
                obsidian-to-lava = false
                void-teleport-members = false
                teleport-warmup = "10s"
                stop-border-crossing = false
                disable-redstone-offline = false
                nether-roof = true
                sync-worth-with-shop = "SELL"
                """;

        ConfigurationNode root = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        SettingsConfiguration config = SettingsConfiguration.load(root);

        assertThat(config.obsidianToLava()).isFalse();
        assertThat(config.voidTeleportMembers()).isFalse();
        assertThat(config.teleportWarmup()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.stopBorderCrossing()).isFalse();
        assertThat(config.disableRedstoneOffline()).isFalse();
        assertThat(config.netherRoof()).isTrue();
        assertThat(config.syncWorthWithShop()).isEqualTo("SELL");
    }
}
