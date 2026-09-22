package com.uxplima.uxmskyblock.bukkit.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A shop prices a material, so a shop may only trade the plain item that material names.
 *
 * <p>Counting by material alone bought an enchanted sword at the price of a sword, a renamed
 * diamond at the price of a diamond and a damaged pickaxe at the price of a new one. The player
 * clicked sell once and everything that made the item worth keeping was gone.
 */
class AShopOnlyTradesPlainItemsTest extends MockBukkitHarness {

    private PlayerMock player;

    @BeforeEach
    void setUpTrader() {
        player = createPlayer("Trader");
    }

    private static ItemStack named(Material material, int amount, String name) {
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack enchanted(Material material) {
        ItemStack stack = new ItemStack(material, 1);
        stack.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        return stack;
    }

    @Test
    @DisplayName("A plain stack is what the shop priced")
    void aPlainStackIsTradable() {
        assertThat(TradableStacks.isPlain(new ItemStack(Material.DIAMOND, 12), Material.DIAMOND))
                .isTrue();
    }

    @Test
    @DisplayName("A stack of another material is never the one priced")
    void anotherMaterialIsNotTradable() {
        assertThat(TradableStacks.isPlain(new ItemStack(Material.DIAMOND, 1), Material.EMERALD))
                .isFalse();
    }

    @Test
    @DisplayName("An enchanted item is worth more than its line says, so it is not the item priced")
    void anEnchantedItemIsNotTradable() {
        assertThat(TradableStacks.isPlain(enchanted(Material.DIAMOND_SWORD), Material.DIAMOND_SWORD))
                .isFalse();
    }

    @Test
    @DisplayName("A renamed item is not the item priced")
    void aRenamedItemIsNotTradable() {
        assertThat(TradableStacks.isPlain(named(Material.DIAMOND, 3, "Wedding ring"), Material.DIAMOND))
                .isFalse();
    }

    @Test
    @DisplayName("Only the plain stacks are counted")
    void onlyPlainStacksAreCounted() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
        player.getInventory().addItem(named(Material.DIAMOND, 5, "Wedding ring"));

        assertThat(TradableStacks.countOf(player, Material.DIAMOND))
                .describedAs("the named stack is not on offer")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("Taking leaves the named stack where it was")
    void takingLeavesTheNamedStack() {
        player.getInventory().addItem(named(Material.DIAMOND, 5, "Wedding ring"));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        TradableStacks.take(player, Material.DIAMOND, 10);

        assertThat(TradableStacks.countOf(player, Material.DIAMOND))
                .describedAs("every plain diamond was taken")
                .isZero();
        assertThat(totalOf(Material.DIAMOND))
                .describedAs("the named stack is still there, untouched")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("A partial take leaves the rest of the stack in its slot")
    void aPartialTakeLeavesTheRest() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        TradableStacks.take(player, Material.DIAMOND, 4);

        assertThat(TradableStacks.countOf(player, Material.DIAMOND)).isEqualTo(6);
    }

    @Test
    @DisplayName("Taking an enchanted tool takes nothing at all")
    void takingNeverTouchesAnEnchantedTool() {
        player.getInventory().addItem(enchanted(Material.DIAMOND_SWORD));

        TradableStacks.take(player, Material.DIAMOND_SWORD, 1);

        assertThat(totalOf(Material.DIAMOND_SWORD))
                .describedAs("the sword the player enchanted is still theirs")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("What is handed over is plain, whatever else the player holds")
    void whatIsGivenIsPlain() {
        player.getInventory().addItem(named(Material.DIAMOND, 1, "Wedding ring"));

        TradableStacks.give(player, Material.DIAMOND, 3);

        assertThat(TradableStacks.countOf(player, Material.DIAMOND)).isEqualTo(3);
        assertThat(totalOf(Material.DIAMOND)).isEqualTo(4);
    }

    /** Every stack of this material, counted the way the broken code counted it. */
    private int totalOf(Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }
}
