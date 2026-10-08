package com.uxplima.uxmskyblock.bukkit.inventory;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Holds an inventory still while an operation that changes it is carried out across a journal.
 *
 * <p>Between a player's inventory changing in memory and the change being written, nothing the player does
 * may change what they carry: no item is thrown, picked up, moved in any window, eaten, placed, used up or
 * worn down, and nothing hurts them. A side put back after a refused commit is put back over exactly what
 * the operation left it, and an item thrown in between would be in the world and back in the inventory both.
 */
public final class HoldStillListener implements Listener {

    private final Predicate<UUID> held;

    /** Holds still every player {@code held} names, for as long as it names them. */
    public HoldStillListener(Predicate<UUID> held) {
        this.held = Objects.requireNonNull(held, "held");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (held.test(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (held.test(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
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
        if (held.test(event.getPlayer().getUniqueId())) {
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
        if (held.test(event.getEntity().getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
    }

    private void hold(Player player, Cancellable event) {
        if (held.test(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
