package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;

import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings("deprecation")
class IslandChatListenerTest extends MockBukkitHarness {

    private IslandChatService chatService;
    private IslandChatListener listener;
    private PlayerMock player;
    private ProfileId profileId;

    @BeforeEach
    void setUp() {
        chatService = mock(IslandChatService.class);
        listener = new IslandChatListener(chatService);
        player = createPlayer("TestPlayer");
        profileId = ProfileId.of(player.getUniqueId());
    }

    @Test
    @DisplayName("chat in GLOBAL channel is not cancelled and not routed to island chat")
    void globalChatNotIntercepted() {
        when(chatService.getChannel(profileId)).thenReturn(IslandChatChannel.GLOBAL);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "Hello world", new HashSet<>());
        listener.onLegacyChat(event);

        assertThat(event.isCancelled()).isFalse();
        verify(chatService).getChannel(profileId);
    }

    @Test
    @DisplayName("chat in ISLAND channel cancels event and dispatches via IslandChatService")
    void islandChatIntercepted() {
        when(chatService.getChannel(profileId)).thenReturn(IslandChatChannel.ISLAND);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "Secret team message", new HashSet<>());
        listener.onLegacyChat(event);

        assertThat(event.isCancelled()).isTrue();
        verify(chatService).sendChat(profileId, player.getName(), "Secret team message");
    }

    @Test
    @DisplayName("player quit notifies IslandChatService to clean up session")
    void playerQuitCleansUp() {
        PlayerQuitEvent event = new PlayerQuitEvent(
                player, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED);
        listener.onPlayerQuit(event);

        verify(chatService).handlePlayerQuit(profileId);
    }

    @Test
    @DisplayName("rate limit exception sends warning message without crashing")
    void rateLimitExceptionHandledGracefully() {
        when(chatService.getChannel(profileId)).thenReturn(IslandChatChannel.ISLAND);
        org.mockito.Mockito.doThrow(
                        new com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException(profileId))
                .when(chatService)
                .sendChat(profileId, player.getName(), "Spam");

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "Spam", new HashSet<>());
        listener.onLegacyChat(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(player.nextComponentMessage()).isNotNull();
    }

    @Test
    @DisplayName("permission denied exception sends warning message without crashing")
    void permissionDeniedHandledGracefully() {
        when(chatService.getChannel(profileId)).thenReturn(IslandChatChannel.ISLAND);
        org.mockito.Mockito.doThrow(new com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException(
                        profileId,
                        com.uxplima.uxmskyblock.core.domain.identity.IslandId.of(java.util.UUID.randomUUID()),
                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission.CHAT_SEND))
                .when(chatService)
                .sendChat(profileId, player.getName(), "Unauthorized");

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "Unauthorized", new HashSet<>());
        listener.onLegacyChat(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(player.nextComponentMessage()).isNotNull();
    }

    @Test
    @DisplayName("no island exception sends warning message without crashing")
    void noIslandHandledGracefully() {
        when(chatService.getChannel(profileId)).thenReturn(IslandChatChannel.ISLAND);
        org.mockito.Mockito.doThrow(new com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException(profileId))
                .when(chatService)
                .sendChat(profileId, player.getName(), "Homeless");

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "Homeless", new HashSet<>());
        listener.onLegacyChat(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(player.nextComponentMessage()).isNotNull();
    }
}
