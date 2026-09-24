package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The ambient checkpoint leaves a player alone while a window that writes their state itself is open.
 *
 * <p>An island vault page is written when its window closes. Items a player took out were already in
 * their inventory, and a checkpoint in between wrote them there while the page still held them: a
 * crash before the window closed kept the items in both places. The checkpoint now waits for such a
 * window to close, and the one after it writes what the player holds.
 */
class ACheckpointWaitsForAWindowThatWritesItselfTest extends MockBukkitHarness {

    private final AtomicInteger writes = new AtomicInteger();
    private SessionBench bench;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server, this::counting);
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName("Nothing is checkpointed while the window is open, and the first checkpoint after writes it all")
    void theCheckpointWaitsForTheWindow() throws Exception {
        PlayerMock player = bench.inPlay(createPlayer("Vaulter"));
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        player.openInventory(Bukkit.createInventory(new SelfWriting(), 27));
        // Taken out of the window: in the player's inventory, still on the page until it closes.
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));

        bench.coordinator.checkpointPlayer(uuid);
        for (int tick = 0; tick < 20; tick++) {
            server.getScheduler().performOneTick();
        }
        Thread.sleep(200);

        assertThat(writes)
                .describedAs("checkpoints written while the window was open")
                .hasValue(0);
        assertThat(SessionBench.items(bench.stored(player))).isEmpty();

        player.closeInventory();
        bench.coordinator.checkpointPlayer(uuid);

        bench.until(() -> assertThat(SessionBench.items(bench.stored(player)))
                .containsExactly(new ItemStack(Material.DIAMOND, 64)));
    }

    @Test
    @DisplayName("The island vault's window is one that writes the player's state itself")
    void theVaultWindowIsOne() {
        assertThat(WritesPlayerStateItself.class)
                .isAssignableFrom(com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow.VaultHolder.class);
    }

    /** The holder of a window like the vault's. */
    private static final class SelfWriting implements InventoryHolder, WritesPlayerStateItself {
        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("a marker");
        }
    }

    private ProfileInventoryCheckpointPort counting(ProfileInventoryCheckpointPort real) {
        return (ProfileInventoryCheckpointPort) Proxy.newProxyInstance(
                ProfileInventoryCheckpointPort.class.getClassLoader(),
                new Class<?>[] {ProfileInventoryCheckpointPort.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("checkpointInventory")) {
                        writes.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
