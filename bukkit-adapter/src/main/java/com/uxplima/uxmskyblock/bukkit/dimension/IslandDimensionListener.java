package com.uxplima.uxmskyblock.bukkit.dimension;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMode;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionAccessResult;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Inbound Bukkit listener intercepting portal travel and routing players to coordinate-mirrored
 * island dimensions or shared worlds (Sections 2.27 & 2.37).
 */
public final class IslandDimensionListener implements Listener {

    private final IslandDimensionService dimensionService;
    private final IslandLocationService islandLocationService;
    private final StarterSchematicEngine schematicEngine;
    private final SchedulerPort schedulerPort;
    private final Function<Player, Optional<ProfileId>> profileResolver;
    private final String overworldName;

    public IslandDimensionListener(
            IslandDimensionService dimensionService,
            IslandLocationService islandLocationService,
            StarterSchematicEngine schematicEngine,
            SchedulerPort schedulerPort,
            Function<Player, Optional<ProfileId>> profileResolver,
            String overworldName) {
        this.dimensionService = Objects.requireNonNull(dimensionService, "dimensionService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.profileResolver = Objects.requireNonNull(profileResolver, "profileResolver must not be null");
        this.overworldName = Objects.requireNonNull(overworldName, "overworldName must not be null");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && cause != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        World currentWorld = from.getWorld();
        if (currentWorld == null) {
            return;
        }

        boolean isInOverworld = currentWorld.getName().equalsIgnoreCase(overworldName);
        if (!isInOverworld) {
            // Returning from dimension to Overworld home
            routeReturnToOverworld(event, player);
            return;
        }

        // Entering dimension from Overworld
        IslandDimensionType targetDimension = (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL)
                ? IslandDimensionType.THE_END
                : IslandDimensionType.NETHER;

        handleDimensionEntry(event, player, targetDimension);
    }

    private void routeReturnToOverworld(PlayerPortalEvent event, Player player) {
        Optional<ProfileId> optProfile = profileResolver.apply(player);
        if (optProfile.isPresent()) {
            Optional<IslandLocation> optHome = islandLocationService.resolveHome(optProfile.get());
            if (optHome.isPresent()) {
                IslandLocation home = optHome.get();
                World overworld = Bukkit.getWorld(home.worldName());
                if (overworld != null) {
                    Location dest = new Location(
                            overworld, home.spawnX(), home.spawnY(), home.spawnZ(), home.spawnYaw(), home.spawnPitch());
                    event.setTo(dest);
                    return;
                }
            }
        }

        World defaultOverworld = Bukkit.getWorld(overworldName);
        if (defaultOverworld != null) {
            event.setTo(defaultOverworld.getSpawnLocation());
        }
    }

    private void handleDimensionEntry(PlayerPortalEvent event, Player player, IslandDimensionType targetDimension) {
        Optional<ProfileId> optProfile = profileResolver.apply(player);
        if (optProfile.isEmpty()) {
            event.setCancelled(true);
            sendMessage(player, "<red>You must have an active island profile to enter portals.</red>");
            return;
        }

        Optional<IslandId> optIslandId = islandLocationService.findIslandId(optProfile.get());
        if (optIslandId.isEmpty()) {
            event.setCancelled(true);
            sendMessage(player, "<red>You do not belong to an active island.</red>");
            return;
        }

        IslandId islandId = optIslandId.get();
        IslandDimensionAccessResult accessResult = dimensionService.checkAccess(islandId, targetDimension);

        switch (accessResult) {
            case IslandDimensionAccessResult.Disabled disabled -> {
                event.setCancelled(true);
                sendMessage(
                        player,
                        "<red>Portal travel to the " + targetDimension.name()
                                + " dimension is currently disabled.</red>");
            }
            case IslandDimensionAccessResult.Locked locked -> {
                event.setCancelled(true);
                sendMessage(
                        player,
                        "<red>The " + targetDimension.name()
                                + " dimension is locked! Unlock the <yellow>"
                                + locked.requiredUpgrade().key()
                                + "</yellow> island upgrade first.</red>");
            }
            case IslandDimensionAccessResult.NoIsland noIsland -> {
                event.setCancelled(true);
                sendMessage(player, "<red>You do not belong to an active island.</red>");
            }
            case IslandDimensionAccessResult.Allowed allowed -> {
                World destWorld = Bukkit.getWorld(allowed.targetWorld());
                if (destWorld == null) {
                    event.setCancelled(true);
                    sendMessage(player, "<red>Dimension world '" + allowed.targetWorld() + "' is not loaded.</red>");
                    return;
                }

                if (allowed.mode() == DimensionMode.SHARED_WORLD) {
                    event.setTo(destWorld.getSpawnLocation());
                    return;
                }

                // PRIVATE_ISLAND mode
                Optional<IslandLocation> optLocation = islandLocationService.findLocation(islandId);
                if (optLocation.isEmpty()) {
                    event.setCancelled(true);
                    sendMessage(player, "<red>Could not resolve island coordinates.</red>");
                    return;
                }

                IslandLocation loc = optLocation.get();
                int centerX = loc.bounds().centerX();
                int centerZ = loc.bounds().centerZ();
                int targetY = 64;

                if (allowed.schematicRequired()) {
                    event.setCancelled(true);
                    sendMessage(
                            player,
                            "<yellow>Generating your " + targetDimension.name()
                                    + " island outpost platform...</yellow>");

                    int chunkX = centerX >> 4;
                    int chunkZ = centerZ >> 4;
                    String worldName = allowed.targetWorld();

                    schedulerPort.onRegion(worldName, chunkX, chunkZ, () -> {
                        World w = Bukkit.getWorld(worldName);
                        if (w != null) {
                            schematicEngine.pasteDimensionPlatform(w, centerX, targetY, centerZ, targetDimension);
                            dimensionService.markDimensionGenerated(islandId, targetDimension);
                        }

                        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            Location targetLoc = new Location(
                                    destWorld,
                                    centerX + 0.5,
                                    targetY + 1.0,
                                    centerZ + 0.5,
                                    player.getLocation().getYaw(),
                                    player.getLocation().getPitch());
                            player.teleport(targetLoc);
                            sendMessage(
                                    player, "<green>Welcome to your " + targetDimension.name() + " island!</green>");
                        });
                    });
                } else {
                    Location targetLoc = new Location(
                            destWorld,
                            centerX + 0.5,
                            targetY + 1.0,
                            centerZ + 0.5,
                            player.getLocation().getYaw(),
                            player.getLocation().getPitch());
                    event.setTo(targetLoc);
                }
            }
        }
    }

    /**
     * Executes dimension travel via command or GUI (/is nether, /is end).
     */
    public void executeDimensionTeleport(Player player, IslandDimensionType targetDimension) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(targetDimension, "targetDimension must not be null");

        Optional<ProfileId> optProfile = profileResolver.apply(player);
        if (optProfile.isEmpty()) {
            sendMessage(player, "<red>You must have an active island profile.</red>");
            return;
        }

        Optional<IslandId> optIslandId = islandLocationService.findIslandId(optProfile.get());
        if (optIslandId.isEmpty()) {
            sendMessage(player, "<red>You do not belong to an active island.</red>");
            return;
        }

        IslandId islandId = optIslandId.get();
        IslandDimensionAccessResult accessResult = dimensionService.checkAccess(islandId, targetDimension);

        switch (accessResult) {
            case IslandDimensionAccessResult.Disabled disabled ->
                sendMessage(
                        player,
                        "<red>Access to the " + targetDimension.name() + " dimension is currently disabled.</red>");
            case IslandDimensionAccessResult.Locked locked ->
                sendMessage(
                        player,
                        "<red>The " + targetDimension.name()
                                + " dimension is locked! Unlock the <yellow>"
                                + locked.requiredUpgrade().key()
                                + "</yellow> island upgrade first.</red>");
            case IslandDimensionAccessResult.NoIsland noIsland ->
                sendMessage(player, "<red>You do not belong to an active island.</red>");
            case IslandDimensionAccessResult.Allowed allowed -> {
                World destWorld = Bukkit.getWorld(allowed.targetWorld());
                if (destWorld == null) {
                    sendMessage(player, "<red>Dimension world '" + allowed.targetWorld() + "' is not loaded.</red>");
                    return;
                }

                if (allowed.mode() == DimensionMode.SHARED_WORLD) {
                    player.teleport(destWorld.getSpawnLocation());
                    sendMessage(player, "<green>Teleported to " + targetDimension.name() + " wilderness.</green>");
                    return;
                }

                // PRIVATE_ISLAND mode
                Optional<IslandLocation> optLocation = islandLocationService.findLocation(islandId);
                if (optLocation.isEmpty()) {
                    sendMessage(player, "<red>Could not resolve island coordinates.</red>");
                    return;
                }

                IslandLocation loc = optLocation.get();
                int centerX = loc.bounds().centerX();
                int centerZ = loc.bounds().centerZ();
                int targetY = 64;
                int chunkX = centerX >> 4;
                int chunkZ = centerZ >> 4;
                String worldName = allowed.targetWorld();

                schedulerPort.onRegion(worldName, chunkX, chunkZ, () -> {
                    World w = Bukkit.getWorld(worldName);
                    if (w != null && allowed.schematicRequired()) {
                        schematicEngine.pasteDimensionPlatform(w, centerX, targetY, centerZ, targetDimension);
                        dimensionService.markDimensionGenerated(islandId, targetDimension);
                    }

                    schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        Location targetLoc = new Location(
                                destWorld,
                                centerX + 0.5,
                                targetY + 1.0,
                                centerZ + 0.5,
                                player.getLocation().getYaw(),
                                player.getLocation().getPitch());
                        player.teleport(targetLoc);
                        sendMessage(player, "<green>Teleported to your " + targetDimension.name() + " island!</green>");
                    });
                });
            }
        }
    }

    private void sendMessage(Player player, String miniMessageText) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(miniMessageText));
            }
        });
    }

    public IslandDimensionService dimensionService() {
        return dimensionService;
    }
}
