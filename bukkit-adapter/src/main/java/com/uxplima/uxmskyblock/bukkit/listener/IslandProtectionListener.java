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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
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
    private volatile @Nullable IslandAdminFreezeService freezeService;
    private final SpatialIslandIndex spatialIndex;
    private final Map<PlayerUuid, ProfileId> activeProfiles = new ConcurrentHashMap<>();
    /**
     * Which island a profile belongs to, remembered rather than asked for.
     *
     * <p>The friendly fire check runs on every hit of every fight, and it used to ask the database
     * twice each time, on the thread the damage event is delivered on. An empty answer is cached as
     * well, because a player with no island is asked about just as often as one with an island.
     */
    private final Map<ProfileId, Optional<IslandId>> islandByProfile = new ConcurrentHashMap<>();

    private final Messages messages;

    private volatile @Nullable Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier;
    private volatile @Nullable Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider;
    private volatile @Nullable Function<ProfileId, ProfileType> profileTypeProvider;

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService,
            @Nullable IslandAdminFreezeService freezeService,
            @Nullable SpatialIslandIndex spatialIndex,
            Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.allianceService = allianceService;
        this.temporaryAccessService = temporaryAccessService;
        this.freezeService = freezeService;
        this.spatialIndex = spatialIndex != null ? spatialIndex : new SpatialIslandIndex(islandStoragePort, null);
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService,
            @Nullable IslandAdminFreezeService freezeService) {
        this(
                islandStoragePort,
                accessService,
                allianceService,
                temporaryAccessService,
                freezeService,
                null,
                Messages.bundled());
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService,
            @Nullable TemporaryAccessService temporaryAccessService) {
        this(islandStoragePort, accessService, allianceService, temporaryAccessService, null, null, Messages.bundled());
    }

    public IslandProtectionListener(
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable IslandAllianceService allianceService) {
        this(islandStoragePort, accessService, allianceService, null, null, null, Messages.bundled());
    }

    public IslandProtectionListener(IslandStoragePort islandStoragePort, IslandAccessService accessService) {
        this(islandStoragePort, accessService, null, null, null, null, Messages.bundled());
    }

    public void setFreezeService(@Nullable IslandAdminFreezeService freezeService) {
        this.freezeService = freezeService;
    }

    private boolean isStaffInspector(Player player) {
        return player.isOp()
                || player.hasPermission("uxmskyblock.admin.bypass")
                || player.hasPermission("uxmskyblock.admin.inspect")
                || player.hasPermission("uxmskyblock.admin.freeze");
    }

    private boolean isIslandFrozen(Island island) {
        if (freezeService != null) {
            return freezeService.isFrozen(island.id()) || island.isFrozen();
        }
        return island.isFrozen();
    }

    public IslandStoragePort islandStoragePort() {
        return islandStoragePort;
    }

    public SpatialIslandIndex spatialIndex() {
        return spatialIndex;
    }

    public void setActiveProfile(PlayerUuid playerUuid, ProfileId profileId) {
        activeProfiles.put(playerUuid, profileId);
    }

    public void removeActiveProfile(PlayerUuid playerUuid) {
        ProfileId profileId = activeProfiles.remove(playerUuid);
        if (profileId != null) {
            islandByProfile.remove(profileId);
        }
    }

    /**
     * The island a profile belongs to, from memory after the first answer. Package private so the
     * guard against the database call returning to this path can ask it directly.
     */
    Optional<IslandId> islandOf(ProfileId profileId) {
        return islandByProfile.computeIfAbsent(profileId, islandStoragePort::findIslandIdByProfileId);
    }

    public void cacheIsland(Island island) {
        if (org.bukkit.Bukkit.getServer() != null
                && !org.bukkit.Bukkit.getWorlds().isEmpty()) {
            for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                spatialIndex.indexIsland(island, world.getName());
            }
        } else {
            spatialIndex.indexIsland(island, "world");
            spatialIndex.indexIsland(island, "skyblock_world");
        }
    }

    public void cacheIsland(Island island, String worldName) {
        spatialIndex.indexIsland(island, worldName);
    }

    public void invalidateIsland(IslandId islandId) {
        spatialIndex.removeIsland(islandId);
        islandByProfile
                .values()
                .removeIf(cached -> cached.isPresent() && cached.get().equals(islandId));
    }

    /**
     * Loads all persisted islands located in the target world on startup to initialize
     * the in-memory spatial protection cache.
     *
     * @param worldName target world identifier
     */
    public void loadPersistedIslands(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        spatialIndex.warmFromStorage(worldName);
    }

    public Optional<Island> findIslandAt(Location location) {
        return spatialIndex.findIslandAt(location);
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
            if (isIslandFrozen(island)) {
                if (!isStaffInspector(player)) {
                    event.setCancelled(true);
                    player.sendMessage(messages.render(player, "protection.frozen"));
                }
                return;
            }
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
            if (isIslandFrozen(island)) {
                if (!isStaffInspector(player)) {
                    event.setCancelled(true);
                    player.sendMessage(messages.render(player, "protection.frozen"));
                }
                return;
            }
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
            if (isIslandFrozen(island)) {
                if (!isStaffInspector(player)) {
                    event.setCancelled(true);
                    player.sendMessage(messages.render(player, "protection.frozen"));
                }
                return;
            }
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
                if (isIslandFrozen(island)) {
                    if (!isStaffInspector(damager)) {
                        event.setCancelled(true);
                        damager.sendMessage(messages.render(damager, "protection.frozen"));
                    }
                    return;
                }
                if (!island.flags().isEnabled(IslandFlags.PVP)) {
                    event.setCancelled(true);
                    return;
                }
                if (allianceService != null && allianceService.isFriendlyFireShieldingEnabled()) {
                    ProfileId damagerProfile = activeProfiles.get(new PlayerUuid(damager.getUniqueId()));
                    ProfileId victimProfile = activeProfiles.get(new PlayerUuid(victim.getUniqueId()));
                    if (damagerProfile != null && victimProfile != null) {
                        Optional<IslandId> damagerIsland = islandOf(damagerProfile);
                        Optional<IslandId> victimIsland = islandOf(victimProfile);
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (isStaffInspector(player)) {
            return;
        }
        Location loc = player.getLocation();
        if (loc == null) {
            return;
        }
        findIslandAt(loc).ifPresent(island -> {
            if (isIslandFrozen(island)) {
                event.setCancelled(true);
                player.sendMessage(messages.render(player, "protection.frozen"));
            }
        });
    }
}
