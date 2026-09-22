package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What the chat commands tell a player, read off the catalogue that ships.
 *
 * <p>The sibling test pins which service call each branch makes. This pins the line that comes back,
 * because a toggle that lands on the island channel and says "public chat again" sends the player's
 * next message to the wrong people while telling them it is going somewhere else.
 */
class WhatTheChatCommandsSayTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private PlayerMock player;
    private IslandChatService chat;
    private PlayerSessionCoordinator sessions;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        player = createPlayer("Talker");
        chat = mock(IslandChatService.class);
        when(chat.hasAlliances()).thenReturn(true);
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandChatCommands commands =
                new IslandChatCommands(() -> chat, inlineScheduler(), Messages.bundled(), sessions);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildChat());
        dispatcher.register(commands.buildSpy());
        dispatcher.register(commands.buildAllianceChat());
        dispatcher.register(commands.buildAllianceChatAlias());
    }

    @Test
    @DisplayName("Switching onto the island channel says the team hears you now")
    void switchingOnSaysTheTeamHears() throws Exception {
        when(chat.toggleChannel(PROFILE)).thenReturn(IslandChatChannel.ISLAND);

        assertThat(run("chat")).singleElement().asString().contains("Island chat enabled");
    }

    @Test
    @DisplayName("Switching off the island channel says public chat hears you again")
    void switchingOffSaysPublicHears() throws Exception {
        when(chat.toggleChannel(PROFILE)).thenReturn(IslandChatChannel.GLOBAL);

        assertThat(run("chat")).singleElement().asString().contains("public chat again");
    }

    @Test
    @DisplayName("A player without an island is told why the channel will not open")
    void noIslandIsSaidOnTheToggle() throws Exception {
        when(chat.toggleChannel(PROFILE)).thenThrow(new NoIslandForChatException(PROFILE));

        assertThat(run("chat")).singleElement().asString().contains("must belong to an island");
    }

    @Test
    @DisplayName("A message the island forbids is refused by name")
    void aForbiddenMessageIsRefused() throws Exception {
        doThrow(new IslandChatPermissionDeniedException(
                        PROFILE, IslandId.of(UUID.randomUUID()), IslandPermission.CHAT_SEND))
                .when(chat)
                .sendChat(PROFILE, "Talker", "hello team");

        assertThat(run("chat hello team"))
                .singleElement()
                .asString()
                .contains("do not have permission to send messages");
    }

    @Test
    @DisplayName("A message that is sent says nothing back to its sender")
    void aSentMessageIsSilent() throws Exception {
        assertThat(run("chat hello team")).isEmpty();
        verify(chat).sendChat(PROFILE, "Talker", "hello team");
    }

    @Test
    @DisplayName("A player whose session is not ready reaches no chat at all")
    void noSessionReachesNothing() throws Exception {
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.empty());

        List<String> said = new ArrayList<>();
        said.addAll(run("chat"));
        said.addAll(run("chat hello"));
        said.addAll(run("allychat"));
        said.addAll(run("ac hello"));

        assertThat(said).hasSize(4).allMatch(line -> line.contains("session is not ready"));
        verify(chat, never()).toggleChannel(any());
        verify(chat, never()).sendChat(any(), anyString(), anyString());
        verify(chat, never()).setChannel(any(), any());
    }

    @Test
    @DisplayName("Spy says which way it switched, for a player allowed to spy")
    void spySaysWhichWay() throws Exception {
        player.addAttachment(MockBukkit.createMockPlugin(), "uxmskyblock.chat.spy", true);
        when(chat.toggleSpy(PROFILE)).thenReturn(true, false);

        assertThat(run("spy")).singleElement().asString().contains("spy enabled");
        assertThat(run("spy")).singleElement().asString().contains("spy disabled");
    }

    @Test
    @DisplayName("Spy refuses a player without the permission and never flips the switch")
    void spyRefusesWithoutPermission() throws Exception {
        assertThat(run("spy")).singleElement().asString().contains("do not have permission to spy");
        verify(chat, never()).toggleSpy(any());
    }

    @Test
    @DisplayName("Alliance chat says the player is now talking to the alliance")
    void allianceChatSaysWhereThePlayerIs() throws Exception {
        when(chat.getChannel(PROFILE)).thenReturn(IslandChatChannel.GLOBAL);
        when(chat.setChannel(PROFILE, IslandChatChannel.ALLIANCE)).thenReturn(IslandChatChannel.ALLIANCE);

        assertThat(run("allychat")).singleElement().asString().contains("talking to your alliance");
    }

    @Test
    @DisplayName("Leaving alliance chat says where the player actually landed, not where they asked to go")
    void leavingAllianceChatSaysWhereThePlayerLanded() throws Exception {
        when(chat.getChannel(PROFILE)).thenReturn(IslandChatChannel.ALLIANCE);
        when(chat.setChannel(PROFILE, IslandChatChannel.GLOBAL)).thenReturn(IslandChatChannel.ISLAND);

        assertThat(run("allychat")).singleElement().asString().contains("Island chat enabled");
    }

    @Test
    @DisplayName("A rate limited alliance line is explained")
    void aRateLimitedAllianceLineIsExplained() throws Exception {
        doThrow(new ChatRateLimitExceededException(PROFILE))
                .when(chat)
                .sendChatOn(PROFILE, "Talker", "hi allies", IslandChatChannel.ALLIANCE);

        assertThat(run("ac hi allies")).singleElement().asString().contains("too quickly");
    }

    @Test
    @DisplayName("An alliance line from a player with no island is refused by name")
    void anAllianceLineWithoutAnIslandIsRefused() throws Exception {
        doThrow(new NoIslandForChatException(PROFILE))
                .when(chat)
                .sendChatOn(PROFILE, "Talker", "hi allies", IslandChatChannel.ALLIANCE);

        assertThat(run("ac hi allies")).singleElement().asString().contains("must belong to an island");
    }

    @Test
    @DisplayName("A server with no chat service says so for every branch")
    void noServiceIsSaid() throws Exception {
        IslandChatCommands off = new IslandChatCommands(() -> null, inlineScheduler(), Messages.bundled(), sessions);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(off.buildChat());
        dispatcher.register(off.buildSpy());
        dispatcher.register(off.buildAllianceChat());

        List<String> said = new ArrayList<>();
        said.addAll(run("chat"));
        said.addAll(run("chat hello"));
        said.addAll(run("spy"));
        said.addAll(run("allychat"));

        assertThat(said).hasSize(4).allMatch(line -> line.contains("not enabled on this node"));
        verifyNoInteractions(chat);
    }

    private List<String> run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PLAIN.serialize(next));
        }
        return lines;
    }

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }
}
