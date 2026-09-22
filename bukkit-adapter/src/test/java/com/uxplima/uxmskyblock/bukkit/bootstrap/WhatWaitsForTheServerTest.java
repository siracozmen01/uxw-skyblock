package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.event.server.ServerLoadEvent;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The plugin loads before the worlds, and what needs them waits.
 *
 * <p>The default world can only be given the island generator by a plugin that has enabled before
 * the world is made, so this one loads at startup. At that moment there are no worlds and none of
 * the plugins it reads through have enabled, so everything that asks for them waits for the server
 * to finish loading.
 */
class WhatWaitsForTheServerTest extends MockBukkitHarness {

    @Test
    @DisplayName("The descriptor asks to load before the worlds")
    void theDescriptorLoadsAtStartup() throws Exception {
        assertThat(Files.readString(Path.of("src", "main", "resources", "paper-plugin.yml")))
                .containsPattern("(?m)^load: STARTUP$");
    }

    @Test
    @DisplayName("Enabled before any world, the plugin waits, and runs the rest once the server has loaded")
    void beforeTheWorldsItWaits() {
        UxMSkyblockPlugin plugin = MockBukkit.load(UxMSkyblockPlugin.class);

        assertThat(plugin.serverIsUp()).describedAs("no world yet").isFalse();

        server.getPluginManager().callEvent(new ServerLoadEvent(ServerLoadEvent.LoadType.STARTUP));

        assertThat(plugin.serverIsUp()).isTrue();
    }

    @Test
    @DisplayName("Enabled on a server that has already loaded, the plugin does not wait")
    void onALoadedServerItDoesNotWait() {
        addWorldMadeBy("world", null);

        UxMSkyblockPlugin plugin = MockBukkit.load(UxMSkyblockPlugin.class);

        assertThat(plugin.serverIsUp()).isTrue();
    }
}
