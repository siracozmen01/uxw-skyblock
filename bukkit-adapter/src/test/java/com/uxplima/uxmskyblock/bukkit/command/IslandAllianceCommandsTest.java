package com.uxplima.uxmskyblock.bukkit.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
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
 * {@code /is alliance} runs, end to end, and gets the handshake round the right way.
 *
 * <p>An invite names a sender island and a target island, and accepting it has to look the pair up
 * in the order it was stored. The caller accepting is the target, so the other island is the sender:
 * get that backwards and the invite is never found, the accept silently does nothing, and nothing in
 * a compile or a type says a word about it. That is the whole reason for this file.
 */
class IslandAllianceCommandsTest {

    private static final IslandId MINE = IslandId.of(UUID.randomUUID());
    private static final IslandId THEIRS = IslandId.of(UUID.randomUUID());
    private static final ProfileId MY_PROFILE = new ProfileId(UUID.randomUUID());
    private static final ProfileId THEIR_PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock me;
    private PlayerMock them;
    private IslandAllianceService alliances;
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
        me = server.addPlayer("Mine");
        them = server.addPlayer("Theirs");
        alliances = mock(IslandAllianceService.class);

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(MY_PROFILE)).thenReturn(Optional.of(MINE));
        when(locations.findIslandId(THEIR_PROFILE)).thenReturn(Optional.of(THEIRS));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(me.getUniqueId())).thenReturn(Optional.of(MY_PROFILE));
        when(sessions.findDurableActiveProfile(them.getUniqueId())).thenReturn(Optional.of(THEIR_PROFILE));

        IslandAllianceCommands commands = new IslandAllianceCommands(
                () -> alliances,
                locations,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
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
    @DisplayName("An invite is sent from my island to theirs")
    void anInviteGoesFromMineToTheirs() throws Exception {
        run("alliance invite Theirs", me);

        verify(alliances).sendInvite(eq(MINE), eq(THEIRS), eq(MY_PROFILE));
    }

    @Test
    @DisplayName("Accepting looks the invite up as they sent it, not as I would have")
    void acceptingUsesTheSendersOrder() throws Exception {
        run("alliance accept Theirs", me);

        // They are the sender because they invited me; I am the target because I am accepting.
        verify(alliances).acceptInvite(eq(THEIRS), eq(MINE));
    }

    @Test
    @DisplayName("Declining uses the same order accepting does")
    void decliningUsesTheSameOrder() throws Exception {
        run("alliance decline Theirs", me);

        verify(alliances).declineInvite(eq(THEIRS), eq(MINE));
    }

    @Test
    @DisplayName("Breaking an alliance names both islands")
    void breakingNamesBoth() throws Exception {
        run("alliance break Theirs", me);

        verify(alliances).removeAlliance(eq(MINE), eq(THEIRS));
    }

    @Test
    @DisplayName("The bare command lists the allies")
    void theBareCommandLists() throws Exception {
        when(alliances.getAllies(MINE)).thenReturn(List.of(THEIRS));

        run("alliance", me);

        verify(alliances).getAllies(MINE);
    }

    @Test
    @DisplayName("An island cannot ally with itself")
    void anIslandCannotAllyWithItself() throws Exception {
        run("alliance invite Mine", me);

        verify(alliances, never()).sendInvite(any(), any(), any());
    }

    @Test
    @DisplayName("A name with no island behind it reaches nothing")
    void anUnknownNameReachesNothing() throws Exception {
        run("alliance invite Nobody", me);

        verify(alliances, never()).sendInvite(any(), any(), any());
    }
}
