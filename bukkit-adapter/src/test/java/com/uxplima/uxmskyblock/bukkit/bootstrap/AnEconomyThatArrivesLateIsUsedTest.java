package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import com.uxplima.uxmskyblock.bukkit.test.InMemoryVaultEconomy;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An economy plugin that registers after this one still moves the island bank's money.
 *
 * <p>The economy was looked up once, as the plugin enabled. An economy plugin that registered its
 * service later left the bridge on the dummy for as long as the server ran, and the island bank
 * refused every deposit and withdrawal. Loading at startup, which the island world's generator needs,
 * makes every economy plugin a late one.
 */
class AnEconomyThatArrivesLateIsUsedTest extends MockBukkitHarness {

    private UxMSkyblockPlugin plugin;

    @BeforeEach
    void setUp() {
        addWorldMadeBy("world", null);
        plugin = MockBukkit.load(UxMSkyblockPlugin.class);
    }

    @Test
    @DisplayName("A deposit after the economy registers late reaches the island bank")
    void aLateEconomyIsBoundWhenItRegisters() {
        assertThat(plugin.bootstrap().economyBridge().isEconomyAvailable())
                .describedAs("no economy yet")
                .isFalse();

        InMemoryVaultEconomy economy = InMemoryVaultEconomy.install(server, 1_000.0);

        assertThat(plugin.bootstrap().economyBridge().isEconomyAvailable())
                .describedAs("bound the moment it registered")
                .isTrue();

        PlayerMock player = createPlayer("Latecomer");
        ProfileId profileId = new ProfileId(player.getUniqueId());
        eventually(() -> assertThat(plugin.bootstrap().sessionCoordinator().activeProfile(player.getUniqueId()))
                .isPresent());
        player.performCommand("is create classic");
        eventually(() -> assertThat(plugin.bootstrap()
                        .persistenceBootstrap()
                        .islandStoragePort()
                        .findIslandIdByProfileId(profileId))
                .isPresent());
        IslandId islandId = plugin.bootstrap()
                .persistenceBootstrap()
                .islandStoragePort()
                .findIslandIdByProfileId(profileId)
                .orElseThrow();

        player.performCommand("is bank deposit 100");

        eventually(() -> {
            Optional<IslandBank> bank =
                    plugin.bootstrap().persistenceBootstrap().islandBankPort().findBankByIslandId(islandId);
            assertThat(bank).isPresent();
            assertThat(bank.get().primaryBalanceMinorUnits()).isEqualTo(10_000L);
        });
        assertThat(economy.balance(player)).isEqualTo(900.0);
    }
}
