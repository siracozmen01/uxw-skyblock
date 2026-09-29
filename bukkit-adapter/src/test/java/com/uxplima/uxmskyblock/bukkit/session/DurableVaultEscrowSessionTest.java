package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A crash while a vault page is open loses nothing the player gained meanwhile, and doubles nothing
 * they moved.
 *
 * <p>The testing standard names this test. A page is written when its window closes, so while it is
 * open the stored page still holds what it held at the open. The checkpoint used to wait for the close,
 * and a crash then lost whatever else the player had gained, a reward in slot 12 or a stack mined. It now
 * writes the player as the stored page leaves them: what they put in the window back with them, what they
 * took out off them, and everything else as it is. What a restart loads is that state beside the page as
 * it was stored, and no item is in both places or in neither.
 */
class DurableVaultEscrowSessionTest extends MockBukkitHarness {

    private static final int PAGE = 27;

    private SessionBench bench;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server);
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName(
            "Five diamond blocks put in and an emerald given in slot 12, then a crash: the stored player holds both")
    void aCrashKeepsTheRewardAndTheDeposit() {
        PlayerMock player = bench.inPlay(createPlayer("Depositor"));
        Inventory window = open(player, List.of());
        window.setItem(4, new ItemStack(Material.DIAMOND_BLOCK, 5));
        player.getInventory().setItem(12, new ItemStack(Material.EMERALD));

        checkpoint(player);

        ItemStack[] stored = storedSlots(player);
        assertThat(stored[12]).isEqualTo(new ItemStack(Material.EMERALD));
        assertThat(SessionBench.items(bench.stored(player)))
                .containsExactlyInAnyOrder(new ItemStack(Material.DIAMOND_BLOCK, 5), new ItemStack(Material.EMERALD));
    }

    @Test
    @DisplayName("What was taken out of the page is not written with the player while the page still holds it")
    void aWithdrawalIsNotWrittenTwice() {
        PlayerMock player = bench.inPlay(createPlayer("Withdrawer"));
        Inventory window = open(player, List.of(new ItemStack(Material.GOLD_INGOT, 3)));
        window.setItem(0, null);
        player.getInventory().setItem(5, new ItemStack(Material.GOLD_INGOT, 3));
        player.getInventory().setItem(6, new ItemStack(Material.BREAD, 2));

        checkpoint(player);

        assertThat(SessionBench.items(bench.stored(player))).containsExactly(new ItemStack(Material.BREAD, 2));
    }

    @Test
    @DisplayName("An item held on the cursor is the player's, and is written with them")
    void theCursorIsThePlayers() {
        PlayerMock player = bench.inPlay(createPlayer("Carrier"));
        open(player, List.of());
        player.setItemOnCursor(new ItemStack(Material.IRON_INGOT, 16));

        checkpoint(player);

        assertThat(SessionBench.items(bench.stored(player))).containsExactly(new ItemStack(Material.IRON_INGOT, 16));
    }

    @Test
    @DisplayName("What was put in and no longer fits the player is not written: the checkpoint waits for the close")
    void aDepositThatDoesNotFitWaits() {
        PlayerMock player = bench.inPlay(createPlayer("Hoarder"));
        long before = bench.stored(player).version();
        Inventory window = open(player, List.of());
        window.setItem(0, new ItemStack(Material.DIAMOND, 64));
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }

        bench.coordinator.checkpointPlayer(new PlayerUuid(player.getUniqueId()));
        for (int tick = 0; tick < 20; tick++) {
            server.getScheduler().performOneTick();
        }

        assertThat(bench.stored(player).version()).isEqualTo(before);
    }

    @Test
    @DisplayName("A slot another plugin rewrote is written as it stands, and nothing is refunded blind")
    void anExternalRewriteIsNotRefunded() {
        PlayerMock player = bench.inPlay(createPlayer("Tampered"));
        Inventory window = open(player, List.of(new ItemStack(Material.GOLD_INGOT, 3)));
        window.setItem(0, null);
        // The player took the gold out, then another plugin wrote dirt over the slot it went to.
        player.getInventory().setItem(2, new ItemStack(Material.DIRT));

        checkpoint(player);

        assertThat(SessionBench.items(bench.stored(player)))
                .describedAs("the gold is on the page as stored, and not given back to the player as well")
                .containsExactly(new ItemStack(Material.DIRT));
    }

    private Inventory open(PlayerMock player, List<ItemStack> page) {
        List<ItemStack> openedWith = new ArrayList<>();
        for (int slot = 0; slot < PAGE; slot++) {
            openedWith.add(slot < page.size() ? page.get(slot) : new ItemStack(Material.AIR));
        }
        IslandVaultWindow.VaultHolder holder = new IslandVaultWindow.VaultHolder(
                IslandId.of(UUID.randomUUID()),
                1,
                new ProfileId(player.getUniqueId()),
                UUID.randomUUID().toString(),
                openedWith,
                true,
                true);
        Inventory window = Bukkit.createInventory(holder, PAGE);
        for (int slot = 0; slot < page.size(); slot++) {
            window.setItem(slot, page.get(slot));
        }
        player.openInventory(window);
        return window;
    }

    private void checkpoint(PlayerMock player) {
        long before = bench.stored(player).version();
        bench.coordinator.checkpointPlayer(new PlayerUuid(player.getUniqueId()));
        bench.until(() -> assertThat(bench.stored(player).version()).isGreaterThan(before));
    }

    private ItemStack[] storedSlots(PlayerMock player) {
        return BukkitInventorySerializer.deserializeItemStacks(
                bench.stored(player).inventoryNbt());
    }
}
