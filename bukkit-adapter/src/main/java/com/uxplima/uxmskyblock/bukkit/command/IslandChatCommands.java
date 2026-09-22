package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island team chat and spy commands:
 * /is chat, /is c, /is spy
 */
public final class IslandChatCommands {

    private final Supplier<@Nullable IslandChatService> chatServiceProvider;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandChatCommands(
            Supplier<@Nullable IslandChatService> chatServiceProvider,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.chatServiceProvider = Objects.requireNonNull(chatServiceProvider, "chatServiceProvider must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildChat() {
        return Cmd.literal("chat")
                .executes(this::executeChatToggle)
                .then(Cmd.argument("message", StringArgumentType.greedyString()).executes(this::executeChatMessage));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildChatAlias() {
        return Cmd.literal("c")
                .executes(this::executeChatToggle)
                .then(Cmd.argument("message", StringArgumentType.greedyString()).executes(this::executeChatMessage));
    }

    /**
     * {@code /is allychat}: put the player on the alliance channel, or take them off it.
     *
     * <p>The design specification publishes this and {@code /is ac} beside it, and the alliance
     * service has carried the switch that turns the channel on since it was written. There was no
     * channel to switch on.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildAllianceChat() {
        return Cmd.literal("allychat").executes(this::executeAllianceToggle);
    }

    /** {@code /is ac [message]}: the short form, which sends one line without leaving your channel. */
    public LiteralArgumentBuilder<CommandSourceStack> buildAllianceChatAlias() {
        return Cmd.literal("ac")
                .executes(this::executeAllianceToggle)
                .then(Cmd.argument("message", StringArgumentType.greedyString())
                        .executes(this::executeAllianceMessage));
    }

    private int executeAllianceToggle(CommandContext<CommandSourceStack> ctx) {
        return withChat(ctx, (player, chatService, profileId) -> {
            if (!chatService.hasAlliances()) {
                onEntity(player, () -> send(player, "chat.alliance_disabled"));
                return;
            }
            try {
                IslandChatChannel current = chatService.getChannel(profileId);
                IslandChatChannel next =
                        current == IslandChatChannel.ALLIANCE ? IslandChatChannel.GLOBAL : IslandChatChannel.ALLIANCE;
                IslandChatChannel landed = chatService.setChannel(profileId, next);
                onEntity(player, () -> {
                    switch (landed) {
                        case ALLIANCE -> send(player, "chat.toggled_alliance");
                        case ISLAND -> send(player, "chat.toggled_island");
                        case GLOBAL -> send(player, "chat.toggled_public");
                    }
                });
            } catch (NoIslandForChatException e) {
                onEntity(player, () -> send(player, "chat.requires_island"));
            }
        });
    }

    private int executeAllianceMessage(CommandContext<CommandSourceStack> ctx) {
        String message = StringArgumentType.getString(ctx, "message");
        return withChat(ctx, (player, chatService, profileId) -> {
            if (!chatService.hasAlliances()) {
                onEntity(player, () -> send(player, "chat.alliance_disabled"));
                return;
            }
            try {
                // One line on the alliance channel, and the player stays on whichever channel they
                // were standing on. A short form that silently moves somebody is a short form that
                // sends their next message to the wrong people.
                chatService.sendChatOn(profileId, player.getName(), message, IslandChatChannel.ALLIANCE);
            } catch (NoIslandForChatException e) {
                onEntity(player, () -> send(player, "chat.requires_island"));
            } catch (IslandChatPermissionDeniedException e) {
                onEntity(player, () -> send(player, "chat.send_denied"));
            } catch (ChatRateLimitExceededException e) {
                onEntity(player, () -> send(player, "chat.rate_limited"));
            }
        });
    }

    /** What a chat command needs: a player, the service, and their own profile, off the thread. */
    @FunctionalInterface
    private interface ChatAction {
        void run(Player player, IslandChatService chatService, ProfileId profileId);
    }

    private int withChat(CommandContext<CommandSourceStack> ctx, ChatAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, "chat.not_enabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> action.run(player, chatService, profileId));
        return Cmd.OK;
    }

    private void onEntity(Player player, Runnable work) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                work.run();
            }
        });
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildSpy() {
        return Cmd.literal("spy").executes(this::executeSpyToggle);
    }

    private int executeChatToggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, "chat.not_enabled");
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
            try {
                IslandChatChannel newChannel = chatService.toggleChannel(profileId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (newChannel == IslandChatChannel.ISLAND) {
                        send(player, "chat.toggled_island");
                    } else {
                        send(player, "chat.toggled_public");
                    }
                });
            } catch (NoIslandForChatException e) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "chat.requires_island"));
            }
        });
        return Cmd.OK;
    }

    private int executeChatMessage(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, "chat.not_enabled");
            return Cmd.OK;
        }
        String message = StringArgumentType.getString(ctx, "message");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            try {
                chatService.sendChat(profileId, player.getName(), message);
            } catch (NoIslandForChatException e) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "chat.requires_island"));
            } catch (IslandChatPermissionDeniedException e) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "chat.send_denied"));
            } catch (ChatRateLimitExceededException e) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "chat.rate_limited"));
            }
        });
        return Cmd.OK;
    }

    private int executeSpyToggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, "chat.not_enabled");
            return Cmd.OK;
        }
        // Only the node this plugin declares. A second, undeclared node was accepted here too, and
        // a skyblock.* wildcard granted for some other plugin let a player read private chat.
        if (!player.hasPermission(CatalogPermissions.CHAT_SPY.node())) {
            send(player, "chat.spy_denied");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        boolean enabled = chatService.toggleSpy(profileId);
        if (enabled) {
            send(player, "chat.spy_enabled");
        } else {
            send(player, "chat.spy_disabled");
        }
        return Cmd.OK;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key) {
        send(audience, messages.render(audience, key));
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
}
