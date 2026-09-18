package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Listens for chat events and routes messages to {@link IslandChatService} when the player
 * has toggled into {@link IslandChatChannel#ISLAND}.
 */
public final class IslandChatListener implements Listener {

    private final IslandChatService chatService;

    public IslandChatListener(IslandChatService chatService) {
        this.chatService = Objects.requireNonNull(chatService, "chatService must not be null");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ProfileId profileId = ProfileId.of(player.getUniqueId());

        if (chatService.getChannel(profileId) == IslandChatChannel.ISLAND) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            try {
                chatService.sendChat(profileId, player.getName(), message);
            } catch (NoIslandForChatException e) {
                player.sendMessage(
                        Component.text("You must belong to an island to use island chat.", NamedTextColor.RED));
            } catch (IslandChatPermissionDeniedException e) {
                player.sendMessage(Component.text(
                        "You do not have permission to send messages in island chat.", NamedTextColor.RED));
            } catch (ChatRateLimitExceededException e) {
                player.sendMessage(
                        Component.text("You are sending messages too quickly. Please slow down.", NamedTextColor.RED));
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ProfileId profileId = ProfileId.of(player.getUniqueId());

        if (chatService.getChannel(profileId) == IslandChatChannel.ISLAND) {
            event.setCancelled(true);
            try {
                chatService.sendChat(profileId, player.getName(), event.getMessage());
            } catch (NoIslandForChatException e) {
                player.sendMessage(
                        Component.text("You must belong to an island to use island chat.", NamedTextColor.RED));
            } catch (IslandChatPermissionDeniedException e) {
                player.sendMessage(Component.text(
                        "You do not have permission to send messages in island chat.", NamedTextColor.RED));
            } catch (ChatRateLimitExceededException e) {
                player.sendMessage(
                        Component.text("You are sending messages too quickly. Please slow down.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        chatService.handlePlayerQuit(ProfileId.of(event.getPlayer().getUniqueId()));
    }
}
