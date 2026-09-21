package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is warp} runs, end to end, through Brigadier.
 *
 * <p>Creating a warp reads where the player stands before anything goes to the scheduler, because on
 * Folia a location read from the async pool throws rather than lagging. That ordering is invisible
 * to a compile and it is the thing most likely to be undone by the next person editing this file,
 * so the test that matters most here is the one that says the warp lands where the player was.
 */
class IslandWarpCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandWarpService warps;
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

    private static Island island() {
        return Island.create(
                ISLAND,
                new IslandBounds(-50, -50, 50, 50, 0, 0, 50),
                PlayerUuid.of(UUID.randomUUID()),
                PROFILE,
                Instant.now());
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer();

        warps = mock(IslandWarpService.class);
        when(warps.getWarps(ISLAND)).thenReturn(List.of());
        when(warps.getMaxAllowedWarps(ISLAND)).thenReturn(3);
        when(warps.getPublicWarps(anyInt(), anyInt())).thenReturn(List.of());

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island()));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandWarpCommands commands = new IslandWarpCommands(
                () -> warps,
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
    @DisplayName("The bare command lists this island's warps and how many slots are left")
    void theBareCommandLists() throws Exception {
        run("warp", player);

        verify(warps).getWarps(ISLAND);
        verify(warps).getMaxAllowedWarps(ISLAND);
    }

    @Test
    @DisplayName("Creating a warp puts it where the player is standing")
    void creatingPutsItWhereTheyStand() throws Exception {
        player.teleport(new org.bukkit.Location(server.getWorld("world"), 12.5, 70.0, -8.5, 90f, 10f));

        run("warp create shop", player);

        verify(warps)
                .createWarp(
                        any(Island.class),
                        eq(PROFILE),
                        eq(WarpName.of("shop")),
                        org.mockito.ArgumentMatchers.argThat(location -> location.x() == 12.5
                                && location.y() == 70.0
                                && location.z() == -8.5
                                && "world".equals(location.worldName())),
                        eq(WarpCategory.GENERAL),
                        any(String.class));
    }

    @Test
    @DisplayName("A named category is used, and an unknown one never reaches the service")
    void aNamedCategoryIsUsed() throws Exception {
        run("warp create market shops", player);
        verify(warps)
                .createWarp(any(), any(), eq(WarpName.of("market")), any(), eq(WarpCategory.SHOPS), any(String.class));

        run("warp create other nonsense", player);
        verify(warps, never())
                .createWarp(any(), any(), eq(WarpName.of("other")), any(), any(WarpCategory.class), any(String.class));
    }

    @Test
    @DisplayName("Deleting names the warp")
    void deletingNamesTheWarp() throws Exception {
        run("warp delete shop", player);

        verify(warps).deleteWarp(any(Island.class), eq(PROFILE), eq(WarpName.of("shop")));
    }

    @Test
    @DisplayName("A role that may not create a warp is told, not thrown at")
    void aRefusedCreateIsExplained() throws Exception {
        doThrow(new SecurityException("not your island"))
                .when(warps)
                .createWarp(any(), any(), any(), any(), any(), any());
        player.nextMessage();

        run("warp create shop", player);

        assertThat(player.nextMessage())
                .describedAs("a refusal the player never sees reads as a command that does nothing")
                .isNotNull();
    }

    @Test
    @DisplayName("Browsing reads the public warps rather than this island's")
    void browsingReadsThePublicOnes() throws Exception {
        run("warp browse", player);

        verify(warps).getPublicWarps(anyInt(), anyInt());
        verify(warps, never()).getWarps(any());
    }
}
