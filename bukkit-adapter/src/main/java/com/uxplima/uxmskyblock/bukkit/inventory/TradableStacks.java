package com.uxplima.uxmskyblock.bukkit.inventory;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What a shop may count, take and hand back.
 *
 * <p>A shop prices a material and nothing else: one line per material, one number beside it. So a
 * shop may only trade the plain item that line names. An enchanted sword, a damaged pickaxe, a
 * renamed block, a filled shulker box and a brewed potion are all worth more than the line says,
 * and a shop that counted them by material alone bought them at the plain price and destroyed
 * everything that made them worth more.
 *
 * <p>A mission that asks for a material is the same line with a count in place of a price. A
 * renamed sword handed in as one sword, or a filled shulker box handed in as one box, loses the
 * player everything it held.
 *
 * <p>The rule is therefore one comparison: a stack trades only when it is similar to a stack fresh
 * off the material. That leaves the player holding their enchanted sword and tells them they do not
 * hold what they offered to sell, which is true.
 */
public final class TradableStacks {

    private TradableStacks() {}

    /** Whether this stack is the plain item a shop line names, with nothing added to it. */
    public static boolean isPlain(ItemStack stack, Material material) {
        Objects.requireNonNull(material, "material must not be null");
        return stack.getType() == material && stack.isSimilar(new ItemStack(material));
    }

    /** How many of the plain item the player holds. Anything enchanted or named is not counted. */
    public static int countOf(Player player, Material material) {
        int held = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && isPlain(stack, material)) {
                held += stack.getAmount();
            }
        }
        return held;
    }

    /** Takes {@code amount} of the plain item, slot by slot, and leaves every other stack alone. */
    public static void take(Player player, Material material, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || !isPlain(stack, material)) {
                continue;
            }
            int taken = Math.min(stack.getAmount(), remaining);
            remaining -= taken;
            if (taken >= stack.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - taken);
                player.getInventory().setItem(slot, stack);
            }
        }
    }

    /** What does not fit is dropped where they stand, because an item on the ground can be picked up. */
    public static void give(Player player, Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, material.getMaxStackSize());
            for (ItemStack overflow : player.getInventory()
                    .addItem(new ItemStack(material, stackSize))
                    .values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            remaining -= stackSize;
        }
    }
}
