package com.uxplima.uxmskyblock.bukkit.listener;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import org.jspecify.annotations.Nullable;

/**
 * Inbound Bukkit listener enforcing island protection boundaries, permissions, and environmental flags.
 */
public final class IslandProtectionListener implements Listener {

    private final IslandStoragePort islandStoragePort;
    private final IslandAccessService accessService;
    private final @Nullable IslandAllianceService allianceService;
    private final @Nullable TemporaryAccessService temporaryAccessService;
    private final Map<PlayerUuid, ProfileId> activeProfiles = new ConcurrentHashMap<>();
    private final Map<IslandId, Island> cachedIslands = new ConcurrentHashMap<>();

    private volatile @Nullable Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier;
    private volatile @Nullable Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider;
    private volatile @Nullable Function<ProfileId, ProfileType> profileTypeProvider;

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.allianceService = allianceService;
        this.temporaryAccessService = temporaryAccessService;
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService) {
        this(islandStoragePort, accessService, allianceService, null);
    }

    public IslandProtectionListener(IslandStoragePort islandStoragePort, IslandAccessService accessService) {
        this(islandStoragePort, accessService, null);
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

    public void setNodeIdentitySupplier(Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier) {
        this.nodeIdentitySupplier = nodeIdentitySupplier;
    }

    public void setSessionRecordProvider(Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider) {
        this.sessionRecordProvider = sessionRecordProvider;
    }

    public void setProfileTypeProvider(Function<ProfileId, ProfileType> profileTypeProvider) {
        this.profileTypeProvider = profileTypeProvider;
    }

    private boolean hasTemporaryAccess(
            PlayerUuid playerUuid, ProfileId profileId, IslandId islandId, PermissionKey permission) {
        if (temporaryAccessService == null) {
            return false;
        }
        CurrentNodeProcessIdentity identity = nodeIdentitySupplier != null
                ? nodeIdentitySupplier.get()
                : new CurrentNodeProcessIdentity("unknown", "default");
        ProfileType type = profileTypeProvider != null ? profileTypeProvider.apply(profileId) : ProfileType.CLASSIC;
        PlayerSessionRecord sessionRecord = sessionRecordProvider != null && playerUuid != null
                ? sessionRecordProvider.apply(playerUuid).orElse(null)
                : null;

        return temporaryAccessService.hasAccess(
                "ISLAND",
                islandId.value().toString(),
                profileId,
                permission,
                Instant.now(),
                identity,
                sessionRecord,
                type);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }

        findIslandAt(event.getBlock().getLocation()).ifPresent(island -> {
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            ProfileId profileId = activeProfiles.get(playerUuid);
            if (profileId == null
                    || (!accessService.canBreak(island, profileId)
                            && !hasTemporaryAccess(
                                    playerUuid, profileId, island.id(), PermissionKey.of("uxm:block.break")))) {
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
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            ProfileId profileId = activeProfiles.get(playerUuid);
            if (profileId == null
                    || (!accessService.canPlace(island, profileId)
                            && !hasTemporaryAccess(
                                    playerUuid, profileId, island.id(), PermissionKey.of("uxm:block.place")))) {
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
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            ProfileId profileId = activeProfiles.get(playerUuid);
            if (profileId == null
                    || (!accessService.canInteract(island, profileId)
                            && !hasTemporaryAccess(
                                    playerUuid, profileId, island.id(), PermissionKey.of("uxm:interact")))) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player damager) {
            if (damager.hasPermission("uxmskyblock.admin.bypass")) {
                return;
            }
            findIslandAt(event.getEntity().getLocation()).ifPresent(island -> {
                if (!island.flags().isEnabled(IslandFlags.PVP)) {
                    event.setCancelled(true);
                    return;
                }
                if (allianceService != null && allianceService.isFriendlyFireShieldingEnabled()) {
                    ProfileId damagerProfile = activeProfiles.get(new PlayerUuid(damager.getUniqueId()));
                    ProfileId victimProfile = activeProfiles.get(new PlayerUuid(victim.getUniqueId()));
                    if (damagerProfile != null && victimProfile != null) {
                        Optional<IslandId> damagerIsland = islandStoragePort.findIslandIdByProfileId(damagerProfile);
                        Optional<IslandId> victimIsland = islandStoragePort.findIslandIdByProfileId(victimProfile);
                        if (damagerIsland.isPresent()
                                && victimIsland.isPresent()
                                && allianceService.areAllied(damagerIsland.get(), victimIsland.get())) {
                            event.setCancelled(true);
                        }
                    }
                }
            });
        }
    }
}
