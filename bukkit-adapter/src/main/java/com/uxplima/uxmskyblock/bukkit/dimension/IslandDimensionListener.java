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

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
    private final Messages messages;

    /** How long a player is treated as already travelling before the portal may start again. */
    private static final java.time.Duration TRAVEL_DEBOUNCE = java.time.Duration.ofSeconds(5);

    /**
     * Who is already on their way through a portal.
     *
     * <p>A cancelled portal event fires again while the player is still standing in the portal, and
     * the travel this starts finishes some hops later. Without this, a player who waits in a portal
     * starts the same journey several times over.
     */
    private final java.util.Set<java.util.UUID> travelling = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public IslandDimensionListener(
            IslandDimensionService dimensionService,
            IslandLocationService islandLocationService,
            StarterSchematicEngine schematicEngine,
            SchedulerPort schedulerPort,
            Function<Player, Optional<ProfileId>> profileResolver,
            String overworldName,
            Messages messages) {
        this.dimensionService = Objects.requireNonNull(dimensionService, "dimensionService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.profileResolver = Objects.requireNonNull(profileResolver, "profileResolver must not be null");
        this.overworldName = Objects.requireNonNull(overworldName, "overworldName must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
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

        // A portal has to be answered before the event returns, and answering it takes three rows:
        // which island the player belongs to, whether the dimension is unlocked, and where the
        // island is. Reading them here stopped the region for as long as the database took, on the
        // thread running the game for everybody around the portal. The portal is refused and the
        // travel is done on the scheduler, which is the path /is nether already takes.
        event.setCancelled(true);
        if (!travelling.add(player.getUniqueId())) {
            return;
        }
        schedulerPort.asyncAfter(TRAVEL_DEBOUNCE, () -> travelling.remove(player.getUniqueId()));

        boolean isInOverworld = currentWorld.getName().equalsIgnoreCase(overworldName);
        if (!isInOverworld) {
            schedulerPort.async(() -> returnToOverworld(player));
            return;
        }

        IslandDimensionType targetDimension = (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL)
                ? IslandDimensionType.THE_END
                : IslandDimensionType.NETHER;

        Optional<ProfileId> optProfile = profileResolver.apply(player);
        if (optProfile.isEmpty()) {
            send(player, "dimension.no_profile");
            return;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> travelToDimension(player, profileId, targetDimension));
    }

    /**
     * Sends the player back to their island, or to the overworld spawn when they have none.
     *
     * <p>Runs on the scheduler: resolving a home is a read, and looking a world up and moving a
     * player belong to the thread that owns them.
     */
    private void returnToOverworld(Player player) {
        Optional<IslandLocation> optHome = profileResolver.apply(player).flatMap(islandLocationService::resolveHome);

        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (!player.isOnline()) {
                return;
            }
            if (optHome.isPresent()) {
                IslandLocation home = optHome.get();
                World overworld = Bukkit.getWorld(home.worldName());
                if (overworld != null) {
                    var unused = player.teleportAsync(new Location(
                            overworld,
                            home.spawnX(),
                            home.spawnY(),
                            home.spawnZ(),
                            home.spawnYaw(),
                            home.spawnPitch()));
                    return;
                }
            }
            World defaultOverworld = Bukkit.getWorld(overworldName);
            if (defaultOverworld != null) {
                var unusedFallback = player.teleportAsync(defaultOverworld.getSpawnLocation());
            }
        });
    }

    /**
     * Executes dimension travel via command or GUI (/is nether, /is end).
     */
    public void executeDimensionTeleport(Player player, IslandDimensionType targetDimension) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(targetDimension, "targetDimension must not be null");

        Optional<ProfileId> optProfile = profileResolver.apply(player);
        if (optProfile.isEmpty()) {
            send(player, "dimension.no_profile");
            return;
        }
        ProfileId profileId = optProfile.get();

        // Which island the caller belongs to, where it is, and whether the dimension is unlocked are
        // three reads. This runs from /is nether and /is end, which Brigadier calls on the thread
        // that owns the player, so the reads go to the scheduler and only the teleport comes back.
        schedulerPort.async(() -> travelToDimension(player, profileId, targetDimension));
    }

    private void travelToDimension(Player player, ProfileId profileId, IslandDimensionType targetDimension) {
        Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
        if (optIslandId.isEmpty()) {
            send(player, "dimension.no_island");
            return;
        }

        IslandId islandId = optIslandId.get();
        IslandDimensionAccessResult accessResult = dimensionService.checkAccess(islandId, targetDimension);

        switch (accessResult) {
            case IslandDimensionAccessResult.Disabled disabled ->
                send(player, "dimension.disabled", dimensionName(player, targetDimension));
            case IslandDimensionAccessResult.Locked locked ->
                send(
                        player,
                        "dimension.locked",
                        dimensionName(player, targetDimension),
                        Placeholder.unparsed("upgrade", locked.requiredUpgrade().key()));
            case IslandDimensionAccessResult.NoIsland noIsland -> send(player, "dimension.no_island");
            case IslandDimensionAccessResult.Allowed allowed -> {
                if (allowed.mode() == DimensionMode.SHARED_WORLD) {
                    // Looking a world up and moving a player both belong to the thread that owns
                    // the player, and this one runs on the scheduler.
                    schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        World sharedWorld = Bukkit.getWorld(allowed.targetWorld());
                        if (sharedWorld == null) {
                            send(
                                    player,
                                    "dimension.world_unloaded",
                                    Placeholder.unparsed("world", allowed.targetWorld()));
                            return;
                        }
                        var unused = player.teleportAsync(sharedWorld.getSpawnLocation());
                        send(player, "dimension.wilderness", dimensionName(player, targetDimension));
                    });
                    return;
                }

                // PRIVATE_ISLAND mode
                Optional<IslandLocation> optLocation = islandLocationService.findLocation(islandId);
                if (optLocation.isEmpty()) {
                    send(player, "dimension.no_coordinates");
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
                        World destWorld = Bukkit.getWorld(worldName);
                        if (destWorld == null) {
                            send(player, "dimension.world_unloaded", Placeholder.unparsed("world", worldName));
                            return;
                        }
                        Location pLoc = player.getLocation();
                        float yaw = pLoc != null ? pLoc.getYaw() : 0.0f;
                        float pitch = pLoc != null ? pLoc.getPitch() : 0.0f;
                        Location targetLoc =
                                new Location(destWorld, centerX + 0.5, targetY + 1.0, centerZ + 0.5, yaw, pitch);
                        var unused = player.teleportAsync(targetLoc);
                        send(player, "dimension.arrived", dimensionName(player, targetDimension));
                    });
                });
            }
        }
    }

    /**
     * Sends one catalogue line to the player, on the thread that owns them.
     *
     * <p>Every line here used to be an English sentence written into the Java, which is a Turkish
     * player reading English at every portal and a translator with nothing to translate. Two of them
     * also pasted an enum constant straight into the sentence, so a player was told about the
     * "THE_END" dimension.
     */
    private void send(Player player, String key, TagResolver... resolvers) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                player.sendMessage(messages.render(player, key, resolvers));
            }
        });
    }

    /** The dimension's name in the player's own language, rather than its enum constant. */
    private TagResolver dimensionName(Player player, IslandDimensionType dimension) {
        return Placeholder.component(
                "dimension",
                messages.renderPlain(
                        player, "dimension.name_" + dimension.name().toLowerCase(java.util.Locale.ROOT)));
    }

    public IslandDimensionService dimensionService() {
        return dimensionService;
    }
}
