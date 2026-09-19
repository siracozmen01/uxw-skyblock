package com.uxplima.uxmskyblock.bukkit.performance;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;

import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Performance optimization listener (Section 2.41 item 3).
 * Suspends redstone current propagation, piston movement, and spawner operations when zero members are online.
 */
public final class IslandRedstoneOptimizationListener implements Listener {

    private final SettingsConfiguration config;
    private final Function<Location, Optional<Island>> islandLookup;
    private final Function<Island, Boolean> onlineMemberCheck;

    public IslandRedstoneOptimizationListener(
            SettingsConfiguration config, Function<Location, Optional<Island>> islandLookup) {
        this(config, islandLookup, IslandRedstoneOptimizationListener::defaultOnlineMemberCheck);
    }

    public IslandRedstoneOptimizationListener(
            SettingsConfiguration config,
            Function<Location, Optional<Island>> islandLookup,
            Function<Island, Boolean> onlineMemberCheck) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.islandLookup = Objects.requireNonNull(islandLookup, "islandLookup must not be null");
        this.onlineMemberCheck = Objects.requireNonNull(onlineMemberCheck, "onlineMemberCheck must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        if (!config.disableRedstoneOffline()) {
            return;
        }

        Block block = event.getBlock();
        Location loc = block.getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        islandLookup.apply(loc).ifPresent(island -> {
            if (!onlineMemberCheck.apply(island)) {
                // Freeze redstone wire current to cancel propagation loop
                event.setNewCurrent(0);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!config.disableRedstoneOffline()) {
            return;
        }

        Location loc = event.getBlock().getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        islandLookup.apply(loc).ifPresent(island -> {
            if (!onlineMemberCheck.apply(island)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!config.disableRedstoneOffline()) {
            return;
        }

        Location loc = event.getBlock().getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        islandLookup.apply(loc).ifPresent(island -> {
            if (!onlineMemberCheck.apply(island)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (!config.afkDisableSpawning()) {
            return;
        }

        Location loc = event.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }

        islandLookup.apply(loc).ifPresent(island -> {
            if (!onlineMemberCheck.apply(island)) {
                event.setCancelled(true);
            }
        });
    }

    private static boolean defaultOnlineMemberCheck(Island island) {
        if (Bukkit.getPlayer(island.ownerPlayerUuid().value()) != null) {
            return true;
        }
        return island.members().values().stream()
                .anyMatch(member -> Bukkit.getPlayer(member.playerUuid().value()) != null);
    }
}
