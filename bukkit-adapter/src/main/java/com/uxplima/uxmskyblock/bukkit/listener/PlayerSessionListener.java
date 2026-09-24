package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Objects;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;

/**
 * Inbound Bukkit listener managing player session registration and lifecycle via {@link PlayerSessionCoordinator}.
 *
 * <p>It is also the gate: a player whose session state is not in their hands does nothing with what
 * they hold. From the join until the durable state is put on them, while a profile switch swaps it
 * and once this server has fenced itself off the session, what they hold is about to be replaced or
 * may no longer be written. An item dropped in that window stayed on the ground and came back with the
 * state as well. Drops, pickups, inventory clicks and drags, block breaks and places and interactions
 * are refused until the session is in play.
 */
public final class PlayerSessionListener implements Listener {

    private final PlayerSessionCoordinator sessionCoordinator;

    public PlayerSessionListener(PlayerSessionCoordinator sessionCoordinator) {
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        sessionCoordinator.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionCoordinator.handlePlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        gate(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            gate(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        gate(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        gate(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        gate(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        gate(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!sessionCoordinator.inPlay(event.getPlayer().getUniqueId())) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        gate(event.getPlayer(), event);
    }

    private void gate(HumanEntity who, Cancellable event) {
        if (!sessionCoordinator.inPlay(who.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
