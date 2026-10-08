package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Hands the events of trade windows, and of players who leave, to {@link Trades}, and holds an inventory
 * still while its trade is carried out.
 *
 * <p>From the moment both players agree until the trade is written or called off, nothing a player does
 * changes what they carry: no item is thrown, picked up, moved, eaten, placed, used up or worn down, and
 * nothing hurts them. A side put back after a refused commit is put back over exactly what the trade left
 * it, and an item thrown in between would be in the world and back in the inventory both.
 */
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            hold(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (trades.exchanging(event.getPlayer().getUniqueId())) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWear(PlayerItemDamageEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEmpty(PlayerBucketEmptyEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFill(PlayerBucketFillEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmourStand(PlayerArmorStandManipulateEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            hold(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player player) {
            hold(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            hold(player, event);
        }
    }

    /** A death nothing could stop keeps what the player carries rather than spilling it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (trades.exchanging(event.getEntity().getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
    }

    private void hold(Player player, Cancellable event) {
        if (trades.exchanging(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
