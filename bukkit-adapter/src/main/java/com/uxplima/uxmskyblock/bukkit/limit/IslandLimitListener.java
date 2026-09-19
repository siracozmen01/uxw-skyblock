package com.uxplima.uxmskyblock.bukkit.limit;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;

/**
 * High-performance event listener enforcing anti-lag tile entity and living entity caps (Section 2.31).
 *
 * <p>Executes strictly within Folia owning region context, intercepting creations and decrements in O(1).
 */
public final class IslandLimitListener implements Listener {

    private final IslandLimitService limitService;
    private final IslandProtectionListener protectionListener;
    private final String bypassPermission;

    public IslandLimitListener(
            IslandLimitService limitService, IslandProtectionListener protectionListener, String bypassPermission) {
        this.limitService = Objects.requireNonNull(limitService, "limitService must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        LimitType limitType = resolveBlockLimitType(block.getType());
        if (limitType == null) {
            return;
        }

        Optional<Island> optIsland = protectionListener.findIslandAt(block.getLocation());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandId islandId = optIsland.get().id();
        Player player = event.getPlayer();
        boolean hasBypass = player.hasPermission(bypassPermission);

        if (!limitService.tryIncrement(islandId, limitType, hasBypass)) {
            event.setCancelled(true);
            int current = limitService.getCount(islandId, limitType);
            int max = limitService.getEffectiveLimit(islandId, limitType);

            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize("<red>Island limit reached for <yellow>"
                            + limitType.name()
                            + "</yellow> (<gray>"
                            + current
                            + "/"
                            + max
                            + "</gray>)!</red>"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        LimitType limitType = resolveBlockLimitType(block.getType());
        if (limitType == null) {
            return;
        }

        protectionListener
                .findIslandAt(block.getLocation())
                .ifPresent(island -> limitService.decrement(island.id(), limitType));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            LimitType lt = resolveBlockLimitType(b.getType());
            if (lt != null) {
                protectionListener
                        .findIslandAt(b.getLocation())
                        .ifPresent(island -> limitService.decrement(island.id(), lt));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            LimitType lt = resolveBlockLimitType(b.getType());
            if (lt != null) {
                protectionListener
                        .findIslandAt(b.getLocation())
                        .ifPresent(island -> limitService.decrement(island.id(), lt));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        LimitType limitType = resolveEntityLimitType(event.getEntity());
        if (limitType == null) {
            return;
        }

        Optional<Island> optIsland = protectionListener.findIslandAt(event.getLocation());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandId islandId = optIsland.get().id();
        if (!limitService.tryIncrement(islandId, limitType, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LimitType limitType = resolveEntityLimitType(event.getEntity());
        if (limitType == null) {
            return;
        }

        protectionListener
                .findIslandAt(event.getEntity().getLocation())
                .ifPresent(island -> limitService.decrement(island.id(), limitType));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        LimitType limitType = resolveEntityLimitType(event.getVehicle());
        if (limitType == null) {
            return;
        }

        protectionListener
                .findIslandAt(event.getVehicle().getLocation())
                .ifPresent(island -> limitService.decrement(island.id(), limitType));
    }

    public static LimitType resolveBlockLimitType(Material material) {
        return switch (material) {
            case HOPPER -> LimitType.HOPPER;
            case PISTON -> LimitType.PISTON;
            case STICKY_PISTON -> LimitType.STICKY_PISTON;
            case OBSERVER -> LimitType.OBSERVER;
            case DROPPER -> LimitType.DROPPER;
            case DISPENSER -> LimitType.DISPENSER;
            case BREWING_STAND -> LimitType.BREWING_STAND;
            case SPAWNER -> LimitType.SPAWNER;
            default -> null;
        };
    }

    public static LimitType resolveEntityLimitType(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Villager) {
            return LimitType.VILLAGER;
        }
        if (entity instanceof ArmorStand) {
            return LimitType.ARMOR_STAND;
        }
        if (entity instanceof Minecart) {
            return LimitType.MINECART;
        }
        if (entity instanceof Boat) {
            return LimitType.BOAT;
        }
        return null;
    }

    public IslandLimitService limitService() {
        return limitService;
    }
}
