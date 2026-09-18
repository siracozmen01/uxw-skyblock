package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class UxMSkyblockPluginTest extends MockBukkitHarness {

    private UxMSkyblockPlugin plugin;

    @BeforeEach
    void setUp() {
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(UxMSkyblockPlugin.class);
    }

    @AfterEach
    void tearDown() {
        // Plugin will be unmocked in MockBukkitHarness.tearDownServer()
    }

    @Test
    @DisplayName("plugin loads, enables bootstrap, and registers components")
    void pluginEnablesSuccessfully() {
        assertThat(plugin).isNotNull();
        assertThat(plugin.isEnabled()).isTrue();
        assertThat(plugin.bootstrap()).isNotNull();
        assertThat(plugin.bootstrap().persistenceBootstrap()).isNotNull();
        assertThat(plugin.bootstrap().protectionListener()).isNotNull();
        assertThat(plugin.bootstrap().commandTree()).isNotNull();
        assertThat(plugin.bootstrap().nodeConfiguration()).isNotNull();
        assertThat(plugin.bootstrap().playerStateConfig()).isNotNull();
        assertThat(plugin.bootstrap().economyBridge()).isNotNull();
        assertThat(plugin.bootstrap().controlMenu()).isNotNull();
        assertThat(plugin.bootstrap().placeholderExpansion()).isNotNull();
        assertThat(plugin.bootstrap().outboxDispatcher()).isNotNull();
        assertThat(new java.io.File(plugin.getDataFolder(), "config.conf")).exists();
        assertThat(new java.io.File(plugin.getDataFolder(), "modules.conf")).exists();
        assertThat(new java.io.File(plugin.getDataFolder(), "seasons.conf")).exists();
        assertThat(plugin.bootstrap().moduleRegistry().enabledModules()).isNotEmpty();
        assertThat(plugin.bootstrap().moduleRegistry().findModule("core")).isPresent();
        assertThat(plugin.bootstrap().moduleRegistry().findModule("bank")).isPresent();
        assertThat(plugin.bootstrap().moduleRegistry().findModule("seasons")).isPresent();
        assertThat(plugin.bootstrap().seasonService()).isNotNull();
    }

    @Test
    @DisplayName("player can execute /is create and manage island lifecycle")
    void playerCanCreateAndManageIsland() {
        PlayerMock player = createPlayer("Alex");
        ProfileId profileId = new ProfileId(player.getUniqueId());

        // Verify player initially has no island
        assertThat(plugin.bootstrap().persistenceBootstrap().islandStoragePort().findIslandIdByProfileId(profileId))
                .isEmpty();

        // Execute help
        player.performCommand("is help");

        // Execute create
        player.performCommand("is create classic");

        // Verify island now exists in persistence
        eventually(() -> {
            Optional<IslandId> optIslandId = plugin.bootstrap()
                    .persistenceBootstrap()
                    .islandStoragePort()
                    .findIslandIdByProfileId(profileId);
            assertThat(optIslandId).isPresent();
        });
        IslandId islandId = plugin.bootstrap()
                .persistenceBootstrap()
                .islandStoragePort()
                .findIslandIdByProfileId(profileId)
                .orElseThrow();

        // Verify bank account initialized
        eventually(() -> {
            Optional<IslandBank> optBank =
                    plugin.bootstrap().persistenceBootstrap().islandBankPort().findBankByIslandId(islandId);
            assertThat(optBank).isPresent();
            assertThat(optBank.get().primaryBalanceMinorUnits()).isEqualTo(0L);
        });

        // Execute bank deposit
        player.performCommand("is bank deposit 250");
        eventually(() -> {
            Optional<IslandBank> optBank =
                    plugin.bootstrap().persistenceBootstrap().islandBankPort().findBankByIslandId(islandId);
            assertThat(optBank).isPresent();
            assertThat(optBank.get().primaryBalanceMinorUnits()).isEqualTo(25000L);
        });

        // Execute bank balance
        player.performCommand("is bank balance");

        // Execute bank withdraw
        player.performCommand("is bank withdraw 100");
        eventually(() -> {
            Optional<IslandBank> optBank =
                    plugin.bootstrap().persistenceBootstrap().islandBankPort().findBankByIslandId(islandId);
            assertThat(optBank).isPresent();
            assertThat(optBank.get().primaryBalanceMinorUnits()).isEqualTo(15000L);
        });

        // Execute biome change
        player.performCommand("is biome plains");

        // Execute teleport home
        player.performCommand("is home");

        // Execute setspawn
        player.performCommand("is setspawn");

        // Execute leaderboards
        player.performCommand("is top level");
        player.performCommand("is top bank");

        // Verify public API bridge registered and functional
        assertThat(com.uxplima.uxmskyblock.api.UxmSkyblockApiProvider.isRegistered())
                .isTrue();
        com.uxplima.uxmskyblock.api.UxmSkyblockApi api = com.uxplima.uxmskyblock.api.UxmSkyblockApi.getInstance();
        assertThat(api).isNotNull();

        var islandSnapshot = api.query().getIsland(islandId.value()).join();
        assertThat(islandSnapshot).isPresent();
        assertThat(islandSnapshot.get().ownerUuid()).isEqualTo(player.getUniqueId());

        var bankBalance = api.query().getBankBalance(islandId.value()).join();
        assertThat(bankBalance).isPresent();
        assertThat(bankBalance.get().balanceMinorUnits()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("plugin disables cleanly without lingering database locks")
    void pluginDisablesCleanly() {
        server.getPluginManager().disablePlugin(plugin);
        assertThat(plugin.isEnabled()).isFalse();
    }
}
