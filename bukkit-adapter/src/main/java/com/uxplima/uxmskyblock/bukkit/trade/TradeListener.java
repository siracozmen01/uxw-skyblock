package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Hands the events of trade windows, and of players who leave, to {@link Trades}. */
public final class TradeListener implements Listener {

    private final Trades trades;

    public TradeListener(Trades trades) {
        this.trades = Objects.requireNonNull(trades, "trades");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        trades.onClick(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        trades.onDrag(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        trades.onClose(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        trades.onLeave(event.getPlayer().getUniqueId());
    }
}
