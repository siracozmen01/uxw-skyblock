package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is ban}, {@code /is unban}, {@code /is bans}, {@code /is lock} and {@code /is unlock},
 * end to end through Brigadier.
 *
 * <p>The ban list could be read and never written, so the visit gate asked a list that was empty
 * everywhere. The lock could only be moved by naming LOCKED to the generic flag command.
 */
class IslandVisitorCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private PlayerMock target;
    private IslandWarpService warps;
    private IslandFlagService flags;
    private Island island;
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
        player = server.addPlayer("Owner");
        target = server.addPlayer("Guest");

        island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                OWNER,
                Instant.now());

        warps = mock(IslandWarpService.class);
        when(warps.getBans(ISLAND)).thenReturn(List.of());

        flags = mock(IslandFlagService.class);
        when(flags.set(any(), any(), anyString(), anyBoolean()))
                .thenReturn(new IslandFlagService.FlagChange.Changed(IslandFlags.LOCKED, true));

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(OWNER)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(OWNER));

        IslandVisitorCommands commands = new IslandVisitorCommands(
                () -> warps,
                flags,
                locations,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildBan());
        dispatcher.register(commands.buildUnban());
        dispatcher.register(commands.buildBans());
        dispatcher.register(commands.buildLock());
        dispatcher.register(commands.buildUnlock());
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
    @DisplayName("A ban names the player the caller typed, so the visit gate has a list to read")
    void aBanReachesTheService() throws Exception {
        run("ban Guest", player);

        verify(warps).banPlayer(eq(island), eq(OWNER), eq(new PlayerUuid(target.getUniqueId())), eq(null));
    }

    @Test
    @DisplayName("A reason after the name is carried with the ban rather than dropped")
    void aReasonIsCarried() throws Exception {
        run("ban Guest griefing the farm", player);

        verify(warps)
                .banPlayer(eq(island), eq(OWNER), eq(new PlayerUuid(target.getUniqueId())), eq("griefing the farm"));
    }

    @Test
    @DisplayName("A name nobody on this server answers to never reaches the ban list")
    void anUnknownNameIsRefused() throws Exception {
        run("ban Nobody", player);

        verify(warps, never()).banPlayer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("An unban names the same player")
    void anUnbanReachesTheService() throws Exception {
        run("unban Guest", player);

        verify(warps).unbanPlayer(eq(island), eq(OWNER), eq(new PlayerUuid(target.getUniqueId())));
    }

    @Test
    @DisplayName("A role that may not ban is told so rather than the command failing silently")
    void aRefusedBanIsAnAnswer() throws Exception {
        when(warps.banPlayer(any(), any(), any(), any())).thenThrow(new SecurityException("no"));

        run("ban Guest", player);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Banning somebody who belongs to the island is refused in words, not with a stack trace")
    void banningAMemberIsAnAnswer() throws Exception {
        when(warps.banPlayer(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("member"));

        run("ban Guest", player);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("The ban list is read for the caller's own island")
    void theListIsReadForTheCallersIsland() throws Exception {
        when(warps.getBans(ISLAND))
                .thenReturn(List.of(
                        new IslandBan(ISLAND, new PlayerUuid(target.getUniqueId()), OWNER, "griefing", Instant.now())));

        run("bans", player);

        verify(warps).getBans(ISLAND);
    }

    @Test
    @DisplayName("/is lock sets the flag rather than turning it the other way")
    void lockSetsRatherThanToggles() throws Exception {
        run("lock", player);

        verify(flags).set(island, OWNER, IslandFlags.LOCKED, true);
    }

    @Test
    @DisplayName("/is unlock clears the same flag")
    void unlockClearsTheFlag() throws Exception {
        run("unlock", player);

        verify(flags).set(island, OWNER, IslandFlags.LOCKED, false);
    }

    @Test
    @DisplayName("Locking twice leaves the island locked, which a toggle would not")
    void lockingTwiceStaysLocked() throws Exception {
        run("lock", player);
        run("lock", player);

        verify(flags, org.mockito.Mockito.times(2)).set(island, OWNER, IslandFlags.LOCKED, true);
        verify(flags, never()).set(any(), any(), anyString(), eq(false));
    }

    @Test
    @DisplayName("The console is told to be a player rather than banning from nowhere")
    void theConsoleIsRefused() throws Exception {
        run("ban Guest", server.getConsoleSender());

        verify(warps, never()).banPlayer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A ban with no name at all is refused by the parser")
    void aBanNeedsAName() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("ban", player))
                .isInstanceOf(Exception.class);
    }
}
