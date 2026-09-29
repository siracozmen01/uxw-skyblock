package com.uxplima.uxmskyblock.bukkit.creative;

import org.bukkit.inventory.ItemStack;

/** Turns a player's items into bytes that are kept on the player, and back. */
public interface InventoryCodec {

    /** The items as bytes. An empty slot is kept as an empty item. */
    byte[] write(ItemStack[] items);

    /** The items the bytes hold, slot for slot. */
    ItemStack[] read(byte[] bytes);
}
