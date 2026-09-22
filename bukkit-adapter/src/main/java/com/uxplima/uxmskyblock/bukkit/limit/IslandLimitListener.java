package com.uxplima.uxmskyblock.bukkit.limit;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.Material;
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

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import org.jspecify.annotations.Nullable;

/**
 * High-performance event listener enforcing anti-lag tile entity and living entity caps (Section 2.31).
 *
 * <p>Executes strictly within Folia owning region context, intercepting creations and decrements in O(1).
 */
public final class IslandLimitListener implements Listener {

    /**
     * What this interaction fires, as the operator wrote it.
     *
     * <p>The sound was written into this file, so a server that wanted a different note, or none,
     * or a title as well, had nowhere to say so. A node built without a list fires nothing.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffects
            effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffectPlayer
            effectPlayer;

    /** Tells this rule what the operator wrote for it. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    private final IslandLimitService limitService;
    private final IslandProtectionListener protectionListener;
    private final String bypassPermission;
    private final Messages messages;

    /**
     * Whoever counts what an island already holds, when nothing has counted it yet.
     *
     * <p>Every count lives in memory, so a restart starts every island at zero: an island that had
     * placed its full allowance of hoppers could place the whole allowance again. The scan that
     * puts that right existed and had no caller.
     */
    private volatile @org.jspecify.annotations.Nullable IslandLimitReconciler reconciler;

    public IslandLimitListener(
            IslandLimitService limitService,
            IslandProtectionListener protectionListener,
            String bypassPermission,
            Messages messages) {
        this.limitService = Objects.requireNonNull(limitService, "limitService must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Tells this listener who counts an island that nothing has counted yet. */
    public void useReconciler(@org.jspecify.annotations.Nullable IslandLimitReconciler reconciler) {
        this.reconciler = reconciler;
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

        IslandLimitReconciler counting = this.reconciler;
        if (counting != null) {
            counting.countOnceIfNeeded(optIsland.get(), block.getWorld().getName());
        }

        if (!limitService.tryIncrement(islandId, limitType, hasBypass)) {
            event.setCancelled(true);
            int current = limitService.getCount(islandId, limitType);
            int max = limitService.getEffectiveLimit(islandId, limitType);

            player.sendMessage(messages.render(
                    player,
                    "limits.reached",
                    Placeholder.unparsed("type", limitType.name()),
                    Placeholder.unparsed("count", Integer.toString(current)),
                    Placeholder.unparsed("max", Integer.toString(max))));
            Location pLoc = player.getLocation();
            com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
            com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
            if (pLoc != null && written != null && plays != null) {
                plays.fire(written, "limit-refused", player);
            }
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

    public static @Nullable LimitType resolveBlockLimitType(Material material) {
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

    public static @Nullable LimitType resolveEntityLimitType(@Nullable Entity entity) {
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
