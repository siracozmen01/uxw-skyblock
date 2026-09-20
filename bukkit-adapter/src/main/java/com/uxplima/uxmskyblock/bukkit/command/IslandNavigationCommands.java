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
import net.kyori.adventure.text.format.NamedTextColor;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
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

    public IslandNavigationCommands(
            IslandLocationService islandLocationService,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            String worldName,
            Supplier<@Nullable IslandDimensionListener> dimensionListenerProvider,
            Supplier<@Nullable IslandNetworkRouter> networkRouterProvider) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.dimensionListenerProvider =
                Objects.requireNonNull(dimensionListenerProvider, "dimensionListenerProvider must not be null");
        this.networkRouterProvider =
                Objects.requireNonNull(networkRouterProvider, "networkRouterProvider must not be null");
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

    private void teleportToIslandLocation(Player player, IslandLocation loc, String successMsg) {
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
            send(player, Component.text(successMsg, NamedTextColor.GREEN));
        } else {
            send(player, Component.text("Island world is currently unloaded.", NamedTextColor.RED));
        }
    }

    private int executeHome(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can teleport to an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandLocation> optLoc = islandLocationService.resolveHome(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optLoc.isEmpty()) {
                    send(
                            player,
                            Component.text(
                                    "You do not have an island yet! Use /is create to get started.",
                                    NamedTextColor.RED));
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
                    send(player, Component.text("Welcome to your island!", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("Island world is currently unloaded.", NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeVisit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can visit islands.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = resolveIslandId(target);
            if (optIsland.isEmpty()) {
                send(player, Component.text("Could not find island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optIsland.get();
            IslandNetworkRouter networkRouter = networkRouterProvider.get();
            if (networkRouter == null) {
                Optional<IslandLocation> optLoc = islandLocationService.findLocation(islandId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (optLoc.isEmpty()) {
                        send(player, Component.text("Target island has no valid location.", NamedTextColor.RED));
                        return;
                    }
                    teleportToIslandLocation(player, optLoc.get(), "Teleported to island " + target + "!");
                });
                return;
            }

            var unused = networkRouter.routeVisit(playerUuid, islandId).thenAccept(outcome -> {
                schedulerPort.onEntity(playerUuid, () -> {
                    switch (outcome) {
                        case RouteOutcome.Local local -> {
                            Optional<IslandLocation> optLoc = islandLocationService.findLocation(local.islandId());
                            if (optLoc.isEmpty()) {
                                send(
                                        player,
                                        Component.text("Target island has no valid location.", NamedTextColor.RED));
                                return;
                            }
                            teleportToIslandLocation(player, optLoc.get(), "Teleported to island " + target + "!");
                        }
                        case RouteOutcome.CrossServer cross -> {
                            send(
                                    player,
                                    Component.text(
                                            "Connecting to "
                                                    + cross.targetNode().value() + "...",
                                            NamedTextColor.YELLOW));
                        }
                        case RouteOutcome.Unavailable unavail -> {
                            send(
                                    player,
                                    Component.text("Cannot visit island: " + unavail.reasonCode(), NamedTextColor.RED));
                        }
                    }
                });
            });
        });
        return Cmd.OK;
    }

    private int executeSetSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can set spawn.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
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
                    send(player, Component.text("Island spawn location updated.", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("You do not have an island.", NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeNether(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can travel to the Nether.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandDimensionListener dimensionListener = dimensionListenerProvider.get();
        if (dimensionListener == null) {
            send(player, Component.text("Multi-dimension travel is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        dimensionListener.executeDimensionTeleport(player, IslandDimensionType.NETHER);
        return Cmd.OK;
    }

    private int executeEnd(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can travel to The End.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandDimensionListener dimensionListener = dimensionListenerProvider.get();
        if (dimensionListener == null) {
            send(player, Component.text("Multi-dimension travel is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        dimensionListener.executeDimensionTeleport(player, IslandDimensionType.THE_END);
        return Cmd.OK;
    }
}
