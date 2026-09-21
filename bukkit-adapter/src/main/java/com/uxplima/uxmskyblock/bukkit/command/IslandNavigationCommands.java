package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.RouteOutcome;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

/**
 * Handles island navigation and travel commands:
 * /is home, /is go, /is visit, /is nether, /is end, /is setspawn
 */
public final class IslandNavigationCommands {

    private final IslandLocationService islandLocationService;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final String worldName;
    private final Supplier<@Nullable IslandDimensionListener> dimensionListenerProvider;
    private final Supplier<@Nullable IslandNetworkRouter> networkRouterProvider;
    private final Messages messages;

    public IslandNavigationCommands(
            IslandLocationService islandLocationService,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            String worldName,
            Supplier<@Nullable IslandDimensionListener> dimensionListenerProvider,
            Supplier<@Nullable IslandNetworkRouter> networkRouterProvider,
            Messages messages) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.dimensionListenerProvider =
                Objects.requireNonNull(dimensionListenerProvider, "dimensionListenerProvider must not be null");
        this.networkRouterProvider =
                Objects.requireNonNull(networkRouterProvider, "networkRouterProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildHome() {
        return Cmd.literal("home").executes(this::executeHome);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildGo() {
        return Cmd.literal("go").executes(this::executeHome);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildVisit() {
        return Cmd.literal("visit")
                .then(Cmd.argument("target", StringArgumentType.word()).executes(this::executeVisit));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildNether() {
        return Cmd.literal("nether").executes(this::executeNether);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildEnd() {
        return Cmd.literal("end").executes(this::executeEnd);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildSetSpawn() {
        return Cmd.literal("setspawn").executes(this::executeSetSpawn);
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    private void send(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }

    private Optional<IslandId> resolveIslandId(String target) {
        return IslandAdminCommands.resolveIslandId(sessionCoordinator, islandLocationService, target);
    }

    private void teleportToIslandLocation(
            Player player, IslandLocation loc, String successKey, TagResolver... resolvers) {
        World world = Bukkit.getWorld(loc.worldName());
        if (world != null) {
            Location destination =
                    new Location(world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch());
            var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                if (Boolean.TRUE.equals(teleported)) {
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                }
            });
            send(player, successKey, resolvers);
        } else {
            send(player, "navigation.world_unloaded");
        }
    }

    private int executeHome(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandLocation> optLoc = islandLocationService.resolveHome(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optLoc.isEmpty()) {
                    send(player, "navigation.no_island_yet");
                    return;
                }
                IslandLocation loc = optLoc.get();
                World world = Bukkit.getWorld(loc.worldName());
                if (world != null) {
                    Location destination = new Location(
                            world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch());
                    var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                        if (Boolean.TRUE.equals(teleported)) {
                            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                            player.setFallDistance(0.0f);
                        }
                    });
                    send(player, "navigation.home_success");
                } else {
                    send(player, "navigation.world_unloaded");
                }
            });
        });

        return Cmd.OK;
    }

    private int executeVisit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = resolveIslandId(target);
            if (optIsland.isEmpty()) {
                send(player, "error.island_not_found", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optIsland.get();
            IslandNetworkRouter networkRouter = networkRouterProvider.get();
            if (networkRouter == null) {
                Optional<IslandLocation> optLoc = islandLocationService.findLocation(islandId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (optLoc.isEmpty()) {
                        send(player, "navigation.no_location");
                        return;
                    }
                    teleportToIslandLocation(
                            player, optLoc.get(), "navigation.visit_success", Placeholder.unparsed("target", target));
                });
                return;
            }

            var unused = networkRouter.routeVisit(playerUuid, islandId).thenAccept(outcome -> {
                // A local route needs the island's location, which is a read. It used to be read
                // inside the hop below, on the thread that owns the player, so every cross island
                // visit put a query under their cursor. The future completes off that thread, which
                // is where a read belongs.
                Optional<IslandLocation> localLocation = outcome instanceof RouteOutcome.Local local
                        ? islandLocationService.findLocation(local.islandId())
                        : Optional.empty();
                schedulerPort.onEntity(playerUuid, () -> {
                    switch (outcome) {
                        case RouteOutcome.Local local -> {
                            if (localLocation.isEmpty()) {
                                send(player, "navigation.no_location");
                                return;
                            }
                            teleportToIslandLocation(
                                    player,
                                    localLocation.get(),
                                    "navigation.visit_success",
                                    Placeholder.unparsed("target", target));
                        }
                        case RouteOutcome.CrossServer cross -> {
                            send(
                                    player,
                                    "command.visit_cross_server",
                                    Placeholder.unparsed(
                                            "node", cross.targetNode().value()));
                        }
                        case RouteOutcome.Unavailable unavail -> {
                            send(
                                    player,
                                    "navigation.visit_unavailable",
                                    Placeholder.unparsed("reason", unavail.reasonCode()));
                        }
                    }
                });
            });
        });
        return Cmd.OK;
    }

    private int executeSetSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        Location current = player.getLocation();
        if (current == null) {
            return Cmd.OK;
        }
        String currentWorld = current.getWorld() != null ? current.getWorld().getName() : this.worldName;
        double x = current.getX();
        double y = current.getY();
        double z = current.getZ();
        float yaw = current.getYaw();
        float pitch = current.getPitch();

        schedulerPort.async(() -> {
            boolean updated = islandLocationService.updateSpawn(profileId, currentWorld, x, y, z, yaw, pitch);
            schedulerPort.onEntity(playerUuid, () -> {
                if (updated) {
                    send(player, "navigation.spawn_updated");
                } else {
                    send(player, "error.no_island");
                }
            });
        });

        return Cmd.OK;
    }

    private int executeNether(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandDimensionListener dimensionListener = dimensionListenerProvider.get();
        if (dimensionListener == null) {
            send(player, "navigation.dimensions_disabled");
            return Cmd.OK;
        }
        dimensionListener.executeDimensionTeleport(player, IslandDimensionType.NETHER);
        return Cmd.OK;
    }

    private int executeEnd(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandDimensionListener dimensionListener = dimensionListenerProvider.get();
        if (dimensionListener == null) {
            send(player, "navigation.dimensions_disabled");
            return Cmd.OK;
        }
        dimensionListener.executeDimensionTeleport(player, IslandDimensionType.THE_END);
        return Cmd.OK;
    }
}
