package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is ban}, {@code /is unban}, {@code /is bans}, {@code /is lock} and {@code /is unlock}:
 * who may come to the island.
 *
 * <p>The ban list has a service, a port, a table and a reader. Nothing could write one. The visit
 * gate asks whether the list names a player, and the list was empty for every island on every
 * server because no command put a name in it.
 *
 * <p>The lock is the same story from the other side. LOCKED is read by the warp path and the visit
 * gate and could only be moved by naming it to the generic flag command or by a Bedrock player
 * finding it in the settings form. The two short names the design document publishes did not exist.
 */
public final class IslandVisitorCommands {

    private final Supplier<@Nullable IslandWarpService> warpServiceProvider;
    private final IslandFlagService flagService;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandVisitorCommands(
            Supplier<@Nullable IslandWarpService> warpServiceProvider,
            IslandFlagService flagService,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.warpServiceProvider = Objects.requireNonNull(warpServiceProvider, "warpServiceProvider must not be null");
        this.flagService = Objects.requireNonNull(flagService, "flagService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBan() {
        return Cmd.literal("ban")
                .then(Cmd.argument("player", StringArgumentType.word())
                        .executes(ctx -> executeBan(ctx, null))
                        .then(Cmd.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> executeBan(ctx, StringArgumentType.getString(ctx, "reason")))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildUnban() {
        return Cmd.literal("unban")
                .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeUnban));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBans() {
        return Cmd.literal("bans").executes(this::executeBans);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLock() {
        return Cmd.literal("lock").executes(ctx -> executeLock(ctx, true));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildUnlock() {
        return Cmd.literal("unlock").executes(ctx -> executeLock(ctx, false));
    }

    private int executeBan(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        String target = StringArgumentType.getString(ctx, "player");
        return onOwnIsland(ctx, (player, service, island, profileId) -> {
            Optional<PlayerUuid> optTarget = resolvePlayer(target);
            if (optTarget.isEmpty()) {
                send(player, "ban.unknown_player", Placeholder.unparsed("player", target));
                return;
            }
            try {
                service.banPlayer(island, profileId, optTarget.get(), reason);
                send(player, "ban.done", Placeholder.unparsed("player", target));
            } catch (SecurityException denied) {
                send(player, "ban.no_permission");
            } catch (IllegalArgumentException refused) {
                // The owner and an active member are both refused by the service, and for the same
                // reason: a ban is for a visitor, and somebody who belongs is removed, not banned.
                send(player, "ban.not_a_visitor", Placeholder.unparsed("player", target));
            }
        });
    }

    private int executeUnban(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        return onOwnIsland(ctx, (player, service, island, profileId) -> {
            Optional<PlayerUuid> optTarget = resolvePlayer(target);
            if (optTarget.isEmpty()) {
                send(player, "ban.unknown_player", Placeholder.unparsed("player", target));
                return;
            }
            try {
                boolean removed = service.unbanPlayer(island, profileId, optTarget.get());
                send(player, removed ? "ban.lifted" : "ban.not_banned", Placeholder.unparsed("player", target));
            } catch (SecurityException denied) {
                send(player, "ban.no_permission");
            }
        });
    }

    private int executeBans(CommandContext<CommandSourceStack> ctx) {
        return onOwnIsland(ctx, (player, service, island, profileId) -> {
            List<IslandBan> bans = service.getBans(island.id());
            send(player, "ban.header", Placeholder.unparsed("count", Integer.toString(bans.size())));
            if (bans.isEmpty()) {
                send(player, "ban.list_empty");
                return;
            }
            for (IslandBan ban : bans) {
                String reason = ban.reason();
                send(
                        player,
                        "ban.entry",
                        Placeholder.unparsed("player", nameOf(ban.bannedPlayerUuid())),
                        Placeholder.unparsed("reason", reason == null ? "" : reason));
            }
        });
    }

    private int executeLock(CommandContext<CommandSourceStack> ctx, boolean locked) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Island> optIsland = ownIsland(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            switch (flagService.set(optIsland.get(), profileId, IslandFlags.LOCKED, locked)) {
                case IslandFlagService.FlagChange.Changed changed ->
                    send(player, changed.enabled() ? "lock.locked" : "lock.unlocked");
                case IslandFlagService.FlagChange.NotAllowed ignored -> send(player, "lock.no_permission");
                case IslandFlagService.FlagChange.IslandMissing ignored -> send(player, "error.no_island");
                case IslandFlagService.FlagChange.UnknownFlag ignored -> send(player, "error.no_island");
            }
        });
        return Cmd.OK;
    }

    /** The name a player reads, which is the offline profile's when the player is away. */
    private static String nameOf(PlayerUuid playerUuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerUuid.value());
        String name = offline.getName();
        return name == null ? playerUuid.value().toString() : name;
    }

    /**
     * The player the caller named, online or not.
     *
     * <p>A ban that only works while the target is standing there is not a ban. An offline player
     * who has played on this server before is resolvable, and one who never has is not, which is
     * the honest answer to a typed name.
     */
    private Optional<PlayerUuid> resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new PlayerUuid(online.getUniqueId()));
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return Optional.of(new PlayerUuid(offline.getUniqueId()));
        }
        return Optional.empty();
    }

    /** What a visitor command needs: a player, the service, their island, and their profile. */
    @FunctionalInterface
    private interface IslandAction {
        void run(Player player, IslandWarpService service, Island island, ProfileId profileId);
    }

    /** Resolves the caller's own island off the thread, then hands it back with the service. */
    private int onOwnIsland(CommandContext<CommandSourceStack> ctx, IslandAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandWarpService service = warpServiceProvider.get();
        if (service == null) {
            send(player, "warp.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Island> optIsland = ownIsland(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            action.run(player, service, optIsland.get(), profileId);
        });
        return Cmd.OK;
    }

    private Optional<Island> ownIsland(ProfileId profileId) {
        Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
        return optIslandId.flatMap(islandLocationService::findIsland);
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
