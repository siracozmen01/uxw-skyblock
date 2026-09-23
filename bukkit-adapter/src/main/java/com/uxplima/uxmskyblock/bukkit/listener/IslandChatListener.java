package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Listens for chat events and routes messages to {@link IslandChatService} when the player
 * has toggled into {@link IslandChatChannel#ISLAND}.
 */
public final class IslandChatListener implements Listener {

    private final IslandChatService chatService;
    private final Function<UUID, Optional<ProfileId>> activeProfileProvider;
    private final Messages messages;
    private final @Nullable BukkitIslandOnlineMemberProvider onlineMembers;

    public IslandChatListener(
            IslandChatService chatService,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            Messages messages,
            @Nullable BukkitIslandOnlineMemberProvider onlineMembers) {
        this.chatService = Objects.requireNonNull(chatService, "chatService must not be null");
        this.activeProfileProvider =
                Objects.requireNonNull(activeProfileProvider, "activeProfileProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.onlineMembers = onlineMembers;
    }

    public IslandChatListener(
            IslandChatService chatService,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            Messages messages) {
        this(chatService, activeProfileProvider, messages, null);
    }

    public IslandChatListener(
            IslandChatService chatService,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            Messages messages,
            @Nullable BukkitIslandOnlineMemberProvider onlineMembers) {
        this(
                chatService,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty(),
                messages,
                onlineMembers);
    }

    public IslandChatListener(
            IslandChatService chatService, @Nullable PlayerSessionCoordinator sessionCoordinator, Messages messages) {
        this(chatService, sessionCoordinator, messages, null);
    }

    public IslandChatListener(IslandChatService chatService, Messages messages) {
        this(chatService, (PlayerSessionCoordinator) null, messages);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Optional<ProfileId> optProfile = activeProfileProvider.apply(player.getUniqueId());
        if (optProfile.isEmpty()) {
            return;
        }

        ProfileId profileId = optProfile.get();
        if (chatService.getChannel(profileId) == IslandChatChannel.ISLAND) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            try {
                chatService.sendChat(profileId, player.getName(), message);
            } catch (NoIslandForChatException e) {
                messages.send(player, "chat.requires_island");
            } catch (IslandChatPermissionDeniedException e) {
                messages.send(player, "chat.send_denied");
            } catch (ChatRateLimitExceededException e) {
                messages.send(player, "chat.rate_limited");
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Optional<ProfileId> optProfile = activeProfileProvider.apply(player.getUniqueId());
        if (optProfile.isEmpty()) {
            return;
        }

        ProfileId profileId = optProfile.get();
        if (chatService.getChannel(profileId) == IslandChatChannel.ISLAND) {
            event.setCancelled(true);
            try {
                chatService.sendChat(profileId, player.getName(), event.getMessage());
            } catch (NoIslandForChatException e) {
                messages.send(player, "chat.requires_island");
            } catch (IslandChatPermissionDeniedException e) {
                messages.send(player, "chat.send_denied");
            } catch (ChatRateLimitExceededException e) {
                messages.send(player, "chat.rate_limited");
            }
        }
    }

    /**
     * Records that this profile is playing here.
     *
     * <p>The online set is kept as players arrive and leave, so a chat message never has to walk
     * every player on the server from a thread that does not own the list. A player arrives when
     * their session is made, not at the join event: at join they have no profile yet, and a member
     * recorded nowhere never heard their island's chat.
     */
    public void onSessionActive(Player player) {
        if (onlineMembers == null) {
            return;
        }
        activeProfileProvider.apply(player.getUniqueId()).ifPresent(onlineMembers::arrived);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Optional<ProfileId> optProfile =
                activeProfileProvider.apply(event.getPlayer().getUniqueId());
        optProfile.ifPresent(chatService::handlePlayerQuit);
        if (onlineMembers != null) {
            optProfile.ifPresent(onlineMembers::left);
        }
    }
}
