package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

/**
 * Writes a vault page back when its window closes.
 *
 * <p>A vault page is held under a lease while it is open, so closing is the commit. Without this the
 * lease would run out on its own and everything the player moved would be gone.
 */
public final class IslandVaultListener implements Listener {

    private final IslandVaultWindow window;

    public IslandVaultListener(IslandVaultWindow window) {
        this.window = Objects.requireNonNull(window, "window must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof IslandVaultWindow.VaultHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        window.commit(player, holder, inventory.getContents());
    }
}
