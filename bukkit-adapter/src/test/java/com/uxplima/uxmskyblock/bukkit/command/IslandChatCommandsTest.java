package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is chat} runs, end to end, through Brigadier.
 *
 * <p>Two branches share every handler: {@code /is chat} and its one letter alias {@code /is c}. An
 * alias that drifts from what it aliases is the quietest kind of broken, because the person who
 * added the drift was only editing one of them.
 */
class IslandChatCommandsTest {

    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandChatService chat;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        chat = mock(IslandChatService.class);
        when(chat.toggleChannel(PROFILE)).thenReturn(IslandChatChannel.ISLAND);
        when(chat.toggleSpy(PROFILE)).thenReturn(true);

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandChatCommands commands = new IslandChatCommands(
                () -> chat,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildChat());
        dispatcher.register(commands.buildChatAlias());
        dispatcher.register(commands.buildSpy());
        dispatcher.register(commands.buildAllianceChat());
        dispatcher.register(commands.buildAllianceChatAlias());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("The bare command switches channel")
    void theBareCommandSwitchesChannel() throws Exception {
        run("chat", player);

        verify(chat).toggleChannel(PROFILE);
        verify(chat, never()).sendChat(any(), any(), any());
    }

    @Test
    @DisplayName("A message is sent rather than switching")
    void aMessageIsSent() throws Exception {
        run("chat hello everyone", player);

        verify(chat).sendChat(eq(PROFILE), any(String.class), eq("hello everyone"));
        verify(chat, never()).toggleChannel(any());
    }

    @Test
    @DisplayName("The one letter alias does exactly what the long name does")
    void theAliasMatchesTheLongName() throws Exception {
        run("c", player);
        verify(chat).toggleChannel(PROFILE);

        run("c hello again", player);
        verify(chat).sendChat(eq(PROFILE), any(String.class), eq("hello again"));
    }

    @Test
    @DisplayName("Spy is its own branch and touches the channel not at all")
    void spyIsItsOwnBranch() throws Exception {
        player.addAttachment(MockBukkit.createMockPlugin(), "uxmskyblock.chat.spy", true);

        run("spy", player);

        verify(chat).toggleSpy(PROFILE);
        verify(chat, never()).toggleChannel(any());
    }

    @Test
    @DisplayName("A player without the permission listens to nothing")
    void spyNeedsThePermission() throws Exception {
        // Reading other islands' chat is a staff power. A player who does not hold the node must not
        // be able to turn it on by typing the command, and the refusal has to be said out loud.
        player.nextMessage();

        run("spy", player);

        verify(chat, never()).toggleSpy(any());
        assertThat(player.nextMessage())
                .describedAs("a refused staff power must be refused in words")
                .isNotNull();
    }

    @Test
    @DisplayName("A rate limited message is explained, not swallowed")
    void aRateLimitedMessageIsExplained() throws Exception {
        doThrow(new ChatRateLimitExceededException(PROFILE)).when(chat).sendChat(any(), any(), any());
        player.nextMessage();

        run("chat too fast", player);

        assertThat(player.nextMessage())
                .describedAs("a message that never went out must say why")
                .isNotNull();
    }

    @Test
    @DisplayName("Chat switched off answers rather than throwing")
    void chatSwitchedOffAnswers() throws Exception {
        IslandChatCommands offline = new IslandChatCommands(
                () -> null,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                mock(PlayerSessionCoordinator.class));
        CommandDispatcher<CommandSourceStack> offlineDispatcher = new CommandDispatcher<>();
        offlineDispatcher.register(offline.buildChat());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        player.nextMessage();

        offlineDispatcher.execute("chat", source);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("The console says nothing on the island channel")
    void theConsoleSaysNothing() throws Exception {
        run("chat hello", server.getConsoleSender());

        verify(chat, never()).sendChat(any(), any(), any());
    }

    @Test
    @DisplayName("/is ac sends one line on the alliance channel and leaves the player where they were")
    void theShortFormSendsOnTheAllianceChannel() throws Exception {
        when(chat.hasAlliances()).thenReturn(true);

        run("ac we are under attack", player);

        verify(chat)
                .sendChatOn(
                        eq(PROFILE),
                        anyString(),
                        eq("we are under attack"),
                        eq(com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.ALLIANCE));
        verify(chat, never()).sendChat(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("/is allychat moves the player onto the alliance channel")
    void allychatMovesThePlayerOntoTheChannel() throws Exception {
        when(chat.hasAlliances()).thenReturn(true);
        when(chat.getChannel(PROFILE)).thenReturn(com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.GLOBAL);
        when(chat.setChannel(any(), any()))
                .thenReturn(com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.ALLIANCE);

        run("allychat", player);

        verify(chat).setChannel(PROFILE, com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.ALLIANCE);
    }

    @Test
    @DisplayName("/is allychat a second time takes the player back off it")
    void allychatTwiceTakesThemOff() throws Exception {
        when(chat.hasAlliances()).thenReturn(true);
        when(chat.getChannel(PROFILE)).thenReturn(com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.ALLIANCE);
        when(chat.setChannel(any(), any()))
                .thenReturn(com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.GLOBAL);

        run("allychat", player);

        verify(chat).setChannel(PROFILE, com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel.GLOBAL);
    }

    @Test
    @DisplayName("A server without alliances answers rather than putting a player on a channel nobody reads")
    void withoutAlliancesNothingIsSent() throws Exception {
        when(chat.hasAlliances()).thenReturn(false);

        run("allychat", player);
        run("ac hello", player);

        verify(chat, never()).setChannel(any(), any());
        verify(chat, never()).sendChatOn(any(), anyString(), anyString(), any());
    }
}
