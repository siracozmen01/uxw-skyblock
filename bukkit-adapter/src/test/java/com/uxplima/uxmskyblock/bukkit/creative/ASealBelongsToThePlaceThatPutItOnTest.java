package com.uxplima.uxmskyblock.bukkit.creative;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A seal is taken off only by the place that put it on. Two modes beat side by side, and one seeing a
 * player off its own ground must not give back what the other keeps.
 */
class ASealBelongsToThePlaceThatPutItOnTest extends MockBukkitHarness {

    private final List<ItemStack[]> kept = new ArrayList<>();

    private final InventoryCodec codec = new InventoryCodec() {
        @Override
        public byte[] write(ItemStack[] items) {
            ItemStack[] copy = new ItemStack[items.length];
            for (int slot = 0; slot < items.length; slot++) {
                copy[slot] = items[slot] == null ? null : items[slot].clone();
            }
            kept.add(copy);
            return ByteBuffer.allocate(4).putInt(kept.size() - 1).array();
        }

        @Override
        public ItemStack[] read(byte[] bytes) {
            return kept.get(ByteBuffer.wrap(bytes).getInt());
        }
    };

    @Test
    @DisplayName("Another place leaves a seal alone, and the place that put it on gives everything back")
    void onlyTheOwnerTakesItOff() {
        SealedInventory course = new SealedInventory(codec, "parkour");
        SealedInventory plot = new SealedInventory(codec, "brix");
        PlayerMock player = createPlayer("Runner");
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(Material.IRON_PICKAXE));

        assertThat(course.seal(player)).isTrue();
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItem(1, new ItemStack(Material.ENDER_PEARL, 16));

        assertThat(plot.unseal(player)).isFalse();
        assertThat(plot.isSealed(player))
                .describedAs("sealed is sealed, whoever put it on")
                .isTrue();
        assertThat(plot.seal(player)).isFalse();
        assertThat(player.getGameMode()).isEqualTo(GameMode.CREATIVE);
        assertThat(player.getInventory().getItem(1)).isEqualTo(new ItemStack(Material.ENDER_PEARL, 16));

        assertThat(course.unseal(player)).isTrue();
        assertThat(player.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_PICKAXE));
        assertThat(player.getInventory().contains(Material.ENDER_PEARL)).isFalse();
        assertThat(SealedInventory.holds(player)).isFalse();
        assertThat(player.getPersistentDataContainer().getKeys())
                .describedAs("nothing of the seal is left written on the player")
                .noneMatch(key -> key.getKey().startsWith("sealed_"));

        assertThat(plot.seal(player))
                .describedAs("once taken off, any place may seal")
                .isTrue();
        assertThat(course.unseal(player)).isFalse();
        assertThat(plot.unseal(player)).isTrue();
    }
}
