package com.uxplima.uxmskyblock.bukkit.creative;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Keeps what a sealed player holds where it was made: they throw nothing and open no ender chest,
 * wherever they stand, because either would carry a creative item out of the place that made it.
 *
 * <p>Each handler runs on the player's own thread and reads only what is written on the player.
 */
public final class SealedInventoryGuard implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(PlayerDropItemEvent event) {
        if (SealedInventory.holds(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChest(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST
                && event.getPlayer() instanceof Player player
                && SealedInventory.holds(player)) {
            event.setCancelled(true);
        }
    }
}
