package com.uxplima.uxmskyblock.bukkit.session;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * An open window that writes the player's state with its own commit, and says what the ambient
 * checkpoint may write while it is open.
 *
 * <p>An island vault page is edited in a window and written when the window closes. Items a player
 * took out were in their inventory while the page still held them, and a checkpoint in between wrote
 * the inventory with the items in it: a crash before the window closed left the items in both places.
 * The checkpoint then waited for the window to close, and everything else the player gained meanwhile,
 * a reward or a stack mined, was lost with a crash. The checkpoint now writes the player as the stored
 * state leaves them, which the window answers.
 */
public interface WritesPlayerStateItself {

    /**
     * The player's inventory contents as the stored state leaves them while {@code window} is open, or
     * nothing for the checkpoint to wait until it closes.
     */
    default ItemStack @Nullable [] inventoryAsStored(Player player, Inventory window) {
        return null;
    }
}
