package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;

/**
 * Inbound Bukkit listener enforcing island protection boundaries, permissions, and environmental flags.
 */
public final class IslandProtectionListener implements Listener {

    private final IslandStoragePort islandStoragePort;
    private final IslandAccessService accessService;
    private final Map<PlayerUuid, ProfileId> activeProfiles = new ConcurrentHashMap<>();
    private final Map<IslandId, Island> cachedIslands = new ConcurrentHashMap<>();

    public IslandProtectionListener(IslandStoragePort islandStoragePort, IslandAccessService accessService) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
    }

    public IslandStoragePort islandStoragePort() {
        return islandStoragePort;
    }

    public void setActiveProfile(PlayerUuid playerUuid, ProfileId profileId) {
        activeProfiles.put(playerUuid, profileId);
    }

    public void removeActiveProfile(PlayerUuid playerUuid) {
        activeProfiles.remove(playerUuid);
    }

    public void cacheIsland(Island island) {
        cachedIslands.put(island.id(), island);
    }

    public void invalidateIsland(IslandId islandId) {
        cachedIslands.remove(islandId);
    }

    /**
     * Loads all persisted islands located in the target world on startup to initialize
     * the in-memory spatial protection cache.
     *
     * @param worldName target world identifier
     */
    public void loadPersistedIslands(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        for (Island island : islandStoragePort.findAllByWorld(worldName)) {
            cacheIsland(island);
        }
    }

    public Optional<Island> findIslandAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        for (Island island : cachedIslands.values()) {
            if (island.bounds().contains(x, z)) {
                return Optional.of(island);
            }
        }
        // Cache miss fallback: query persisted spatial boundary in database
        Optional<Island> persisted =
                islandStoragePort.findIslandByLocation(location.getWorld().getName(), x, z);
        persisted.ifPresent(this::cacheIsland);
        return persisted;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }

        findIslandAt(event.getBlock().getLocation()).ifPresent(island -> {
            ProfileId profileId = activeProfiles.get(new PlayerUuid(player.getUniqueId()));
            if (profileId == null || !accessService.canBreak(island, profileId)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }

        findIslandAt(event.getBlock().getLocation()).ifPresent(island -> {
            ProfileId profileId = activeProfiles.get(new PlayerUuid(player.getUniqueId()));
            if (profileId == null || !accessService.canPlace(island, profileId)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!event.hasBlock() || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }

        findIslandAt(event.getClickedBlock().getLocation()).ifPresent(island -> {
            ProfileId profileId = activeProfiles.get(new PlayerUuid(player.getUniqueId()));
            if (profileId == null || !accessService.canInteract(island, profileId)) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player damager) {
            if (damager.hasPermission("uxmskyblock.admin.bypass")) {
                return;
            }
            findIslandAt(event.getEntity().getLocation()).ifPresent(island -> {
                if (!island.flags().isEnabled(IslandFlags.PVP)) {
                    event.setCancelled(true);
                }
            });
        }
    }
}
