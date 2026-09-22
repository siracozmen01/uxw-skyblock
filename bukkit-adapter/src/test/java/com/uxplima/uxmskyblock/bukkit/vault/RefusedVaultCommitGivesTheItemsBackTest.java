package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A refused vault commit gives the items back.
 *
 * <p>Closing the vault window is the commit. The window is gone from the screen by the time the
 * commit runs, so a refusal, an expired lease, another node taking the page, a database that will
 * not answer, used to end with a line of chat and nothing else: the items were not in the page,
 * because the write failed, and not with the player, because they had put them in the vault.
 *
 * <p>What goes back is the difference between the window at close and the page at open, never the
 * whole window. The page still holds what it held, so handing back all of it would duplicate every
 * stack the player never touched.
 */
class RefusedVaultCommitGivesTheItemsBackTest {

    private ServerMock server;
    private IslandVaultService vaultService;
    private IslandVaultWindow window;

    /** Runs scheduled work inline, so a test reads in the order it was written. */
    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        vaultService = mock(IslandVaultService.class);
        window = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                inlineScheduler(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static IslandVaultWindow.VaultHolder holderOpenedWith(List<ItemStack> openedWith) {
        return new IslandVaultWindow.VaultHolder(
                IslandId.of(UUID.randomUUID()),
                1,
                new ProfileId(UUID.randomUUID()),
                UUID.randomUUID().toString(),
                openedWith,
                true,
                true);
    }

    private static int countOf(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void refuseEveryCommit() {
        doThrow(new IllegalStateException("the lease expired"))
                .when(vaultService)
                .commitVaultPage(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("What the player put in comes back when the commit is refused")
    void whatWasAddedComesBack() {
        refuseEveryCommit();
        PlayerMock player = server.addPlayer();
        player.getInventory().clear();

        // The page held sixteen cobblestone when the window opened; it holds forty at close.
        IslandVaultWindow.VaultHolder holder = holderOpenedWith(List.of(new ItemStack(Material.COBBLESTONE, 16)));
        ItemStack[] atClose = new ItemStack[] {new ItemStack(Material.COBBLESTONE, 40)};

        window.commit(player, holder, atClose);

        assertThat(countOf(player, Material.COBBLESTONE))
                .describedAs("the twenty four they added come back, and the sixteen still in the page do not")
                .isEqualTo(24);
    }

    @Test
    @DisplayName("A stack that never moved is not handed back, because the page still holds it")
    void whatNeverMovedIsNotDuplicated() {
        refuseEveryCommit();
        PlayerMock player = server.addPlayer();
        player.getInventory().clear();

        IslandVaultWindow.VaultHolder holder = holderOpenedWith(List.of(new ItemStack(Material.DIAMOND, 5)));
        ItemStack[] atClose = new ItemStack[] {new ItemStack(Material.DIAMOND, 5)};

        window.commit(player, holder, atClose);

        assertThat(countOf(player, Material.DIAMOND))
                .describedAs("handing back the whole window would duplicate every untouched stack")
                .isZero();
    }

    @Test
    @DisplayName("A commit that succeeds hands nothing back")
    void aGoodCommitHandsNothingBack() {
        PlayerMock player = server.addPlayer();
        player.getInventory().clear();

        IslandVaultWindow.VaultHolder holder = holderOpenedWith(List.of());
        window.commit(player, holder, new ItemStack[] {new ItemStack(Material.GOLD_INGOT, 3)});

        assertThat(countOf(player, Material.GOLD_INGOT)).isZero();
    }
}
