package com.uxplima.uxmskyblock.bukkit.creative;

import org.bukkit.inventory.ItemStack;

/** The server's own item format, which carries every component an item has across versions. */
public final class ServerInventoryCodec implements InventoryCodec {

    @Override
    public byte[] write(ItemStack[] items) {
        ItemStack[] filled = new ItemStack[items.length];
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            filled[slot] = item == null ? ItemStack.empty() : item;
        }
        return ItemStack.serializeItemsAsBytes(filled);
    }

    @Override
    public ItemStack[] read(byte[] bytes) {
        return ItemStack.deserializeItemsFromBytes(bytes);
    }
}
