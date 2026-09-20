package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
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
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandChatCommands(
            Supplier<@Nullable IslandChatService> chatServiceProvider,
            SchedulerPort schedulerPort,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.chatServiceProvider = Objects.requireNonNull(chatServiceProvider, "chatServiceProvider must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
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

    public LiteralArgumentBuilder<CommandSourceStack> buildSpy() {
        return Cmd.literal("spy").executes(this::executeSpyToggle);
    }

    private int executeChatToggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can use island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, Component.text("Island chat is not enabled on this node.", NamedTextColor.RED));
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
            try {
                IslandChatChannel newChannel = chatService.toggleChannel(profileId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (newChannel == IslandChatChannel.ISLAND) {
                        send(
                                player,
                                Component.text(
                                        "Island chat enabled. All chat messages will now go to your island team.",
                                        NamedTextColor.GREEN));
                    } else {
                        send(
                                player,
                                Component.text(
                                        "Island chat disabled. Chat messages will now go to public chat.",
                                        NamedTextColor.YELLOW));
                    }
                });
            } catch (NoIslandForChatException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You must belong to an island to use island chat.", NamedTextColor.RED)));
            }
        });
        return Cmd.OK;
    }

    private int executeChatMessage(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can use island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, Component.text("Island chat is not enabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        String message = StringArgumentType.getString(ctx, "message");
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
            try {
                chatService.sendChat(profileId, player.getName(), message);
            } catch (NoIslandForChatException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You must belong to an island to use island chat.", NamedTextColor.RED)));
            } catch (IslandChatPermissionDeniedException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You do not have permission to send messages in island chat.",
                                        NamedTextColor.RED)));
            } catch (ChatRateLimitExceededException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You are sending messages too quickly. Please slow down.",
                                        NamedTextColor.RED)));
            }
        });
        return Cmd.OK;
    }

    private int executeSpyToggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can spy on island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandChatService chatService = chatServiceProvider.get();
        if (chatService == null) {
            send(player, Component.text("Island chat is not enabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (!player.hasPermission(CatalogPermissions.CHAT_SPY.node()) && !player.hasPermission("skyblock.chat.spy")) {
            send(player, Component.text("You do not have permission to spy on island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        boolean enabled = chatService.toggleSpy(profileId);
        if (enabled) {
            send(player, Component.text("Island chat spy enabled.", NamedTextColor.GREEN));
        } else {
            send(player, Component.text("Island chat spy disabled.", NamedTextColor.YELLOW));
        }
        return Cmd.OK;
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
}
