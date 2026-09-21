package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
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
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Named island homes: {@code /is sethome}, {@code /is home <name>}, {@code /is homes} and
 * {@code /is delhome}.
 *
 * <p>The service behind these has existed since the enterprise foundation work, with its own
 * storage table and its own tests, and no command ever reached it. Bare {@code /is home} keeps its
 * old meaning, the island spawn, so nobody's muscle memory breaks.
 */
public final class IslandHomeCommands {

    private final Supplier<@Nullable HomeService> homeServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final HomeConfiguration configuration;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandHomeCommands(
            Supplier<@Nullable HomeService> homeServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            HomeConfiguration configuration,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.homeServiceProvider = Objects.requireNonNull(homeServiceProvider, "homeServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildSetHome() {
        return Cmd.literal("sethome")
                .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeSetHome));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildNamedHome() {
        return Cmd.literal("homes").executes(this::executeListHomes);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDeleteHome() {
        return Cmd.literal("delhome")
                .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeDeleteHome));
    }

    /** Travelling to a named home. Bare {@code /is home} stays with the island spawn. */
    public LiteralArgumentBuilder<CommandSourceStack> buildTravelHome() {
        return Cmd.literal("gohome")
                .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeTravelHome));
    }

    private int executeSetHome(CommandContext<CommandSourceStack> ctx) {
        return withHome(ctx, (player, service, profileId) -> {
            String name = StringArgumentType.getString(ctx, "name");
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            Location at = player.getLocation();
            if (at == null || at.getWorld() == null) {
                send(player, "home.location_unreadable");
                return;
            }
            int allowance = configuration.allowanceFor(player::hasPermission);
            HomeService.SetHomeResult result = service.setHome(
                    profileId,
                    optIsland.get(),
                    name,
                    HomeScope.PERSONAL,
                    at.getWorld().getName(),
                    at.getX(),
                    at.getY(),
                    at.getZ(),
                    at.getYaw(),
                    at.getPitch(),
                    allowance);

            switch (result) {
                case HomeService.SetHomeResult.Success success ->
                    send(
                            player,
                            "home.set",
                            Placeholder.unparsed("name", success.home().name()));
                case HomeService.SetHomeResult.LimitExceeded limit ->
                    send(
                            player,
                            "home.limit_reached",
                            Placeholder.unparsed("current", Integer.toString(limit.currentCount())),
                            Placeholder.unparsed("max", Integer.toString(limit.maxAllowed())));
            }
        });
    }

    private int executeTravelHome(CommandContext<CommandSourceStack> ctx) {
        return withHome(ctx, (player, service, profileId) -> {
            String name = StringArgumentType.getString(ctx, "name");
            Optional<Home> optHome = service.getHome(profileId, name);
            if (optHome.isEmpty()) {
                send(player, "home.unknown", Placeholder.unparsed("name", name));
                return;
            }
            Home home = optHome.get();
            World world = Bukkit.getWorld(home.worldName());
            if (world == null) {
                send(player, "navigation.world_unloaded");
                return;
            }
            Location target = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
            var unused = player.teleportAsync(target);
            send(player, "home.travelled", Placeholder.unparsed("name", home.name()));
        });
    }

    private int executeListHomes(CommandContext<CommandSourceStack> ctx) {
        return withHome(ctx, (player, service, profileId) -> {
            List<Home> homes = service.listHomes(profileId);
            int allowance = configuration.allowanceFor(player::hasPermission);
            send(
                    player,
                    "home.list_header",
                    Placeholder.unparsed("count", Integer.toString(homes.size())),
                    Placeholder.unparsed("max", Integer.toString(allowance)));
            if (homes.isEmpty()) {
                send(player, "home.list_empty");
                return;
            }
            for (Home home : homes) {
                send(
                        player,
                        "home.list_entry",
                        Placeholder.unparsed("name", home.name()),
                        Placeholder.unparsed("world", home.worldName()),
                        Placeholder.unparsed("x", Long.toString(Math.round(home.x()))),
                        Placeholder.unparsed("y", Long.toString(Math.round(home.y()))),
                        Placeholder.unparsed("z", Long.toString(Math.round(home.z()))));
            }
        });
    }

    private int executeDeleteHome(CommandContext<CommandSourceStack> ctx) {
        return withHome(ctx, (player, service, profileId) -> {
            String name = StringArgumentType.getString(ctx, "name");
            if (service.deleteHome(profileId, name)) {
                send(player, "home.deleted", Placeholder.unparsed("name", name));
            } else {
                send(player, "home.unknown", Placeholder.unparsed("name", name));
            }
        });
    }

    /** The three checks every one of these verbs needs, in one place. */
    private int withHome(CommandContext<CommandSourceStack> ctx, HomeAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        HomeService service = homeServiceProvider.get();
        if (service == null || !configuration.enabled()) {
            send(player, "home.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    action.run(player, service, profileId);
                }
            });
        });
        return Cmd.OK;
    }

    @FunctionalInterface
    private interface HomeAction {
        void run(Player player, HomeService service, ProfileId profileId);
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
