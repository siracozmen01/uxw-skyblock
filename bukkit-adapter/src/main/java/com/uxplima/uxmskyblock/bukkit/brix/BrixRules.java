package com.uxplima.uxmskyblock.bukkit.brix;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * What cannot happen on a plot: nothing hurts, nobody goes hungry, no item lies on the ground and no
 * ender chest opens. The last two keep what was made in creative on the plot, where a thrown item or an
 * ender chest would carry it off.
 *
 * <p>Each handler runs on the thread that owns the place the event happens in, and every answer about
 * the plot comes from memory.
 */
public final class BrixRules implements Listener {

    private final BrixService service;
    private final IslandProtectionListener islands;
    private final BrixConfiguration.Rules rules;

    public BrixRules(BrixService service, IslandProtectionListener islands, BrixConfiguration.Rules rules) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // The void is left to hurt: a player falling for ever is worse than one who fell.
        if (rules.noDamage()
                && event.getEntity() instanceof Player player
                && event.getCause() != EntityDamageEvent.DamageCause.VOID
                && onPlot(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (rules.noHunger()
                && event.getFoodLevel() < event.getEntity().getFoodLevel()
                && onPlot(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(PlayerDropItemEvent event) {
        if (onPlot(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent event) {
        if (onPlot(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChest(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST
                && event.getPlayer() instanceof Player player
                && (SealedInventory.holds(player) || onPlot(player.getLocation()))) {
            event.setCancelled(true);
        }
    }

    private boolean onPlot(@Nullable Location at) {
        if (at == null) {
            return false;
        }
        Optional<Island> island = islands.findIslandAt(at);
        return island.isPresent() && service.isPlot(island.get().id());
    }
}
