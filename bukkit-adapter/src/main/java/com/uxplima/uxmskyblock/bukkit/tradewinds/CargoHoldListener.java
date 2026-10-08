package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Hands the events of cargo hold windows, and of players who leave, to {@link CargoHolds}. */
public final class CargoHoldListener implements Listener {

    private final CargoHolds holds;

    public CargoHoldListener(CargoHolds holds) {
        this.holds = Objects.requireNonNull(holds, "holds");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        holds.onClick(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        holds.onDrag(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        holds.onClose(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        holds.onLeave(event.getPlayer().getUniqueId());
    }
}
