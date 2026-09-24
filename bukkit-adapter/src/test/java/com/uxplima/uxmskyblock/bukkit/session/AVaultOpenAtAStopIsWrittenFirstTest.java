package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.vault.StaleVaultSessionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A vault window a player still has open when the server stops is written before the stop writes them.
 *
 * <p>The plugin is disabled before players are disconnected, so the window's close event never reached
 * the listener. The page kept what it held and the stop wrote the player holding what they had taken
 * out of it: a restart doubled every item taken out of an open vault, and lost every item put in.
 */
class AVaultOpenAtAStopIsWrittenFirstTest extends MockBukkitHarness {

    private SessionBench bench;
    private IslandVaultService vaultService;
    private IslandVaultWindow window;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server);
        vaultService = mock(IslandVaultService.class);
        window = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                new InlineSchedulerPort(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                bench.coordinator);
        bench.coordinator.whenStopping(window::writeBeforeStop);
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName("The open page is written with the player's state before the stop writes the player")
    void theOpenPageIsWrittenFirst() {
        PlayerMock player = openOnAPageOfFiveDiamonds("Stopper");

        bench.coordinator.shutdown();

        verify(vaultService).commitVaultPage(any(), any(), any(), notNull(), any());
        assertThat(player.getInventory().contains(Material.DIAMOND))
                .describedAs("taken out of a page that was written, so theirs to keep")
                .isTrue();
    }

    @Test
    @DisplayName("A page that cannot be written takes back what the player took out, so the stop does not keep it")
    void aRefusedPageTakesItBack() {
        doThrow(new StaleVaultSessionException("the lease ran out"))
                .when(vaultService)
                .commitVaultPage(any(), any(), any(), any(), any());
        PlayerMock player = openOnAPageOfFiveDiamonds("Taker");

        bench.coordinator.shutdown();

        assertThat(player.getInventory().contains(Material.DIAMOND)).isFalse();
        assertThat(SessionBench.items(bench.stored(player)))
                .describedAs("the stop's own write")
                .noneMatch(item -> item.getType() == Material.DIAMOND);
    }

    /** A player in play with a page of five diamonds open, having taken all five out. */
    private PlayerMock openOnAPageOfFiveDiamonds(String name) {
        PlayerMock player = bench.inPlay(createPlayer(name));
        IslandVaultWindow.VaultHolder holder = new IslandVaultWindow.VaultHolder(
                IslandId.of(UUID.randomUUID()),
                1,
                Objects.requireNonNull(bench.coordinator.getActiveSession(player.getUniqueId()))
                        .activeProfileId(),
                UUID.randomUUID().toString(),
                List.of(new ItemStack(Material.DIAMOND, 5)),
                true,
                true);
        Inventory page = Bukkit.createInventory(holder, 9);
        player.openInventory(page);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        return player;
    }
}
