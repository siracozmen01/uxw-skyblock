package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteExpiredException;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteNotFoundException;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.alliance.AlreadyAlliedException;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.alliance.SelfAllianceNotAllowedException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is alliance}: the islands yours has an understanding with.
 *
 * <p>The alliance service, its invite handshake, its ally limit, its friendly fire shielding and
 * its privileged visit access were all built and none of it was reachable. Friendly fire shielding
 * in particular was running on every damage event, deciding against a table no player could ever
 * put a row into.
 */
public final class IslandAllianceCommands {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandAllianceCommands.class.getName());

    private final Supplier<@Nullable IslandAllianceService> allianceServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandAllianceCommands(
            Supplier<@Nullable IslandAllianceService> allianceServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.allianceServiceProvider =
                Objects.requireNonNull(allianceServiceProvider, "allianceServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** The same branch under another word, for the name a document publishes. */
    public LiteralArgumentBuilder<CommandSourceStack> buildAlias(String verb) {
        return branch(verb);
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return branch("alliance");
    }

    private LiteralArgumentBuilder<CommandSourceStack> branch(String verb) {
        return Cmd.literal(verb)
                .executes(this::executeList)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.literal("invites").executes(this::executeInvites))
                .then(Cmd.literal("invite")
                        .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeInvite)))
                .then(Cmd.literal("accept")
                        .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeAccept)))
                .then(Cmd.literal("decline")
                        .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeDecline)))
                .then(Cmd.literal("break")
                        .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeBreak)));
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        return onOwnIsland(ctx, (player, service, islandId) -> {
            List<IslandId> allies = service.getAllies(islandId);
            onEntity(player, () -> {
                send(player, "alliance.header", Placeholder.unparsed("count", Integer.toString(allies.size())));
                if (allies.isEmpty()) {
                    send(player, "alliance.empty");
                    return;
                }
                for (IslandId ally : allies) {
                    send(
                            player,
                            "alliance.entry",
                            Placeholder.unparsed("island", ally.value().toString()));
                }
            });
        });
    }

    private int executeInvites(CommandContext<CommandSourceStack> ctx) {
        return onOwnIsland(ctx, (player, service, islandId) -> {
            List<IslandAllianceInvite> invites = service.getPendingInvites(islandId);
            onEntity(player, () -> {
                send(player, "alliance.invites_header");
                if (invites.isEmpty()) {
                    send(player, "alliance.invites_empty");
                    return;
                }
                for (IslandAllianceInvite invite : invites) {
                    send(
                            player,
                            "alliance.invite_entry",
                            Placeholder.unparsed(
                                    "island", invite.senderIslandId().value().toString()));
                }
            });
        });
    }

    private int executeInvite(CommandContext<CommandSourceStack> ctx) {
        return betweenTwoIslands(ctx, (player, service, mine, theirs, targetName) -> {
            try {
                service.sendInvite(mine, theirs, activeProfile(player).orElseThrow());
                onEntity(player, () -> send(player, "alliance.invited", Placeholder.unparsed("player", targetName)));
            } catch (RuntimeException refused) {
                refuse(player, refused);
            }
        });
    }

    private int executeAccept(CommandContext<CommandSourceStack> ctx) {
        // The sender of the invite is the other island, and this island is the target.
        return betweenTwoIslands(ctx, (player, service, mine, theirs, targetName) -> {
            try {
                service.acceptInvite(theirs, mine);
                onEntity(player, () -> send(player, "alliance.accepted", Placeholder.unparsed("player", targetName)));
            } catch (RuntimeException refused) {
                refuse(player, refused);
            }
        });
    }

    private int executeDecline(CommandContext<CommandSourceStack> ctx) {
        return betweenTwoIslands(ctx, (player, service, mine, theirs, targetName) -> {
            service.declineInvite(theirs, mine);
            onEntity(player, () -> send(player, "alliance.declined", Placeholder.unparsed("player", targetName)));
        });
    }

    private int executeBreak(CommandContext<CommandSourceStack> ctx) {
        return betweenTwoIslands(ctx, (player, service, mine, theirs, targetName) -> {
            service.removeAlliance(mine, theirs);
            onEntity(player, () -> send(player, "alliance.broken", Placeholder.unparsed("player", targetName)));
        });
    }

    /**
     * Tells a player why an alliance move was refused, from the catalogue.
     *
     * <p>The refusal used to reach the player as the exception's own sentence, in English, with both
     * islands' ids in it. The kind of refusal picks the line now; anything unexpected says it could
     * not be done, and the sentence goes to the log.
     */
    private void refuse(Player player, RuntimeException refused) {
        switch (refused) {
            case SelfAllianceNotAllowedException _ -> onEntity(player, () -> send(player, "alliance.not_yourself"));
            case AlreadyAlliedException _ -> onEntity(player, () -> send(player, "alliance.already_allied"));
            case AllianceInviteNotFoundException _ -> onEntity(player, () -> send(player, "alliance.no_invite"));
            case AllianceInviteExpiredException _ -> onEntity(player, () -> send(player, "alliance.invite_expired"));
            case AllianceLimitExceededException full ->
                onEntity(
                        player,
                        () -> send(
                                player,
                                "alliance.limit_reached",
                                Placeholder.unparsed("max", Integer.toString(full.maxAllowed()))));
            default -> {
                LOGGER.log(java.util.logging.Level.WARNING, "An alliance move could not be made", refused);
                onEntity(player, () -> send(player, "alliance.failed"));
            }
        }
    }

    @FunctionalInterface
    private interface IslandAction {
        void run(Player player, IslandAllianceService service, IslandId islandId);
    }

    @FunctionalInterface
    private interface PairAction {
        void run(Player player, IslandAllianceService service, IslandId mine, IslandId theirs, String targetName);
    }

    private int onOwnIsland(CommandContext<CommandSourceStack> ctx, IslandAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandAllianceService service = allianceServiceProvider.get();
        if (service == null) {
            send(player, "alliance.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                onEntity(player, () -> send(player, "error.no_island"));
                return;
            }
            action.run(player, service, optIsland.get());
        });
        return Cmd.OK;
    }

    /** Resolves this island and the named player's island, both off the thread that asked. */
    private int betweenTwoIslands(CommandContext<CommandSourceStack> ctx, PairAction action) {
        String targetName = StringArgumentType.getString(ctx, "player");
        return onOwnIsland(ctx, (player, service, mine) -> {
            Optional<IslandId> theirs = islandOf(targetName);
            if (theirs.isEmpty()) {
                onEntity(
                        player,
                        () -> send(player, "alliance.no_such_island", Placeholder.unparsed("player", targetName)));
                return;
            }
            if (theirs.get().equals(mine)) {
                onEntity(player, () -> send(player, "alliance.not_yourself"));
                return;
            }
            action.run(player, service, mine, theirs.get(), targetName);
        });
    }

    /** The island of a player named on the command line, online or not. */
    private Optional<IslandId> islandOf(String name) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid;
        if (online != null) {
            uuid = online.getUniqueId();
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (!offline.hasPlayedBefore() && !offline.isOnline()) {
                return Optional.empty();
            }
            uuid = offline.getUniqueId();
        }
        return sessionCoordinator.findDurableActiveProfile(uuid).flatMap(islandLocationService::findIslandId);
    }

    private void onEntity(Player player, Runnable work) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                work.run();
            }
        });
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
