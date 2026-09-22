package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    private SchedulerPort scheduler;
    private PlayerMock player;
    private IslandWarpService warps;
    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private boolean teleportAttempted;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
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
        when(warps.safeSpotFor(any(), any())).thenReturn(publicWarp().location());

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island()));

        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        scheduler = inlineScheduler();
        IslandWarpCommands commands = new IslandWarpCommands(
                () -> warps,
                locations,
                scheduler,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
        teleportAttempted = false;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Moving a warp puts it where the player is standing")
    void movingAWarpPutsItHere() throws Exception {
        com.uxplima.uxmskyblock.core.domain.warp.IslandWarp moved =
                mock(com.uxplima.uxmskyblock.core.domain.warp.IslandWarp.class);
        when(moved.name()).thenReturn(com.uxplima.uxmskyblock.core.domain.warp.WarpName.of("home"));
        when(warps.relocateWarp(any(), any(), any(), any())).thenReturn(moved);

        run("warp move home", player);

        org.mockito.ArgumentCaptor<com.uxplima.uxmskyblock.core.domain.warp.WarpLocation> where =
                org.mockito.ArgumentCaptor.forClass(com.uxplima.uxmskyblock.core.domain.warp.WarpLocation.class);
        verify(warps)
                .relocateWarp(
                        any(),
                        org.mockito.ArgumentMatchers.eq(PROFILE),
                        org.mockito.ArgumentMatchers.eq(com.uxplima.uxmskyblock.core.domain.warp.WarpName.of("home")),
                        where.capture());
        assertThat(where.getValue().worldName())
                .describedAs("where the player is standing, read before anything went async")
                .isEqualTo(player.getLocation().getWorld().getName());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Moving a warp this island does not have says so")
    void movingAnUnknownWarpSaysSo() throws Exception {
        when(warps.relocateWarp(any(), any(), any(), any()))
                .thenThrow(new com.uxplima.uxmskyblock.core.domain.warp.WarpNotFoundException(ISLAND, "nowhere"));

        run("warp move nowhere", player);

        assertThat(player.nextMessage())
                .describedAs("told there is no such warp")
                .isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A role that may not touch warps cannot move one either")
    void arefusedRoleCannotMove() throws Exception {
        when(warps.relocateWarp(any(), any(), any(), any())).thenThrow(new SecurityException("no"));

        run("warp move home", player);

        assertThat(player.nextMessage()).describedAs("the refusal").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Browsing a category asks for that category, not for every public warp")
    void browsingACategoryNarrowsTheRead() throws Exception {
        when(warps.getPublicWarpsByCategory(
                        any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        run("warp browse shops", player);

        verify(warps)
                .getPublicWarpsByCategory(
                        org.mockito.ArgumentMatchers.eq(com.uxplima.uxmskyblock.core.domain.warp.WarpCategory.SHOPS),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(warps, org.mockito.Mockito.never())
                .getPublicWarps(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Browsing without a category still reads every public warp")
    void browsingWithoutACategoryReadsEverything() throws Exception {
        when(warps.getPublicWarps(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        run("warp browse", player);

        verify(warps).getPublicWarps(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A word that is not a category is refused, and the categories are named")
    void anunknownCategoryIsRefused() throws Exception {
        run("warp browse nonsense", player);

        verify(warps, org.mockito.Mockito.never())
                .getPublicWarpsByCategory(
                        any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(player.nextMessage())
                .describedAs("the refusal names what to type instead")
                .isNotNull();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Runs the line and records whether the command got as far as putting the player down.
     *
     * <p>MockBukkit does not implement {@code teleportAsync}. It raises an exception JUnit reads as
     * an assumption failure, which turns the test into a silent skip rather than a failure: two
     * tests here reported green that way before this caught it. The teleport is turned into a fact
     * to assert on instead.
     */
    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        try {
            dispatcher.execute(line, source);
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException unimplemented) {
            assertThat(unimplemented.getStackTrace())
                    .describedAs("only the teleport is allowed to be unimplemented here")
                    .anyMatch(frame -> frame.getMethodName().contains("teleport"));
            teleportAttempted = true;
        }
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

    @Test
    @DisplayName("Visiting another island's warp asks the gate for that island, not the caller's own")
    void visitingAsksTheGateForTheTargetIsland() throws Exception {
        when(warps.resolveVisit(any(), any(), eq(PROFILE), eq(WarpName.of("shop"))))
                .thenReturn(publicWarp());

        run("warp visit " + ISLAND.value() + " shop", player);

        verify(warps).resolveVisit(any(), any(PlayerUuid.class), eq(PROFILE), eq(WarpName.of("shop")));
        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("The safe spot is searched on the region thread that owns the destination")
    void theSafeSpotIsSearchedOnTheOwningRegion() throws Exception {
        when(warps.resolveVisit(any(), any(), any(), any())).thenReturn(publicWarp());

        run("warp visit " + ISLAND.value() + " shop", player);

        // 40.5 and -72.5 sit in chunk 2 and chunk -5, and the block read belongs to that thread.
        verify(scheduler).onRegion(eq("world"), eq(2), eq(-5), any(Runnable.class));
        verify(warps).safeSpotFor(any(), any());
        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("A banned visitor is refused before any block is read")
    void aBannedVisitorNeverReachesTheSafeSpotSearch() throws Exception {
        doThrow(new com.uxplima.uxmskyblock.core.domain.warp.PlayerBannedFromIslandException(
                        ISLAND, PlayerUuid.of(player.getUniqueId()), null))
                .when(warps)
                .resolveVisit(any(), any(), any(), any());

        run("warp visit " + ISLAND.value() + " shop", player);

        verify(warps, never()).safeSpotFor(any(), any());
        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A locked island is refused before any block is read")
    void aLockedIslandNeverReachesTheSafeSpotSearch() throws Exception {
        doThrow(new com.uxplima.uxmskyblock.core.domain.warp.IslandLockedException(ISLAND))
                .when(warps)
                .resolveVisit(any(), any(), any(), any());

        run("warp visit " + ISLAND.value() + " shop", player);

        verify(warps, never()).safeSpotFor(any(), any());
        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("An island closed to visitors is refused before any block is read")
    void aClosedIslandNeverReachesTheSafeSpotSearch() throws Exception {
        doThrow(new com.uxplima.uxmskyblock.core.domain.warp.IslandClosedToVisitorsException(ISLAND))
                .when(warps)
                .resolveVisit(any(), any(), any(), any());

        run("warp visit " + ISLAND.value() + " shop", player);

        verify(warps, never()).safeSpotFor(any(), any());
        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A warp locked to visitors is refused before any block is read")
    void aLockedWarpNeverReachesTheSafeSpotSearch() throws Exception {
        doThrow(new com.uxplima.uxmskyblock.core.domain.warp.WarpLockedException(WarpName.of("shop")))
                .when(warps)
                .resolveVisit(any(), any(), any(), any());

        run("warp visit " + ISLAND.value() + " shop", player);

        verify(warps, never()).safeSpotFor(any(), any());
        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A destination with nowhere safe to stand is an answer, not a teleport into lava")
    void anUnsafeDestinationIsAnAnswer() throws Exception {
        when(warps.resolveVisit(any(), any(), any(), any())).thenReturn(publicWarp());
        doThrow(new com.uxplima.uxmskyblock.core.domain.warp.UnsafeTeleportDestinationException(
                        publicWarp().location(), "no_safe_spot"))
                .when(warps)
                .safeSpotFor(any(), any());

        run("warp visit " + ISLAND.value() + " shop", player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A name that belongs to no island never reaches the gate")
    void anUnknownOwnerNeverReachesTheGate() throws Exception {
        run("warp visit Nobody shop", player);

        verify(warps, never()).resolveVisit(any(), any(), any(), any());
        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A visit with no warp name is refused by the parser")
    void aVisitNeedsAWarpName() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("warp visit " + ISLAND.value(), player))
                .isInstanceOf(Exception.class);
    }

    /** A public warp on the target island, at coordinates that sit in a chunk worth naming. */
    private static com.uxplima.uxmskyblock.core.domain.warp.IslandWarp publicWarp() {
        return new com.uxplima.uxmskyblock.core.domain.warp.IslandWarp(
                com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId.of(UUID.randomUUID()),
                ISLAND,
                WarpName.of("shop"),
                new com.uxplima.uxmskyblock.core.domain.warp.WarpLocation("world", 40.5, 64.0, -72.5, 0.0f, 0.0f),
                "OAK_SIGN",
                WarpCategory.SHOPS,
                false,
                Instant.now(),
                Instant.now());
    }

    @Test
    @DisplayName("Locking one warp reaches the setter that nothing used to call")
    void lockingOneWarpReachesTheSetter() throws Exception {
        run("warp lock shop", player);

        verify(warps).setWarpLock(any(), eq(PROFILE), eq(WarpName.of("shop")), eq(true));
    }

    @Test
    @DisplayName("Unlocking it again is the same setter the other way")
    void unlockingIsTheSameSetter() throws Exception {
        run("warp unlock shop", player);

        verify(warps).setWarpLock(any(), eq(PROFILE), eq(WarpName.of("shop")), eq(false));
    }

    @Test
    @DisplayName("The short /is warplock the document publishes is the same verb")
    void theShortFormIsTheSameVerb() throws Exception {
        CommandDispatcher<CommandSourceStack> shortForm = new CommandDispatcher<>();
        IslandWarpCommands commands = new IslandWarpCommands(
                () -> warps,
                locations,
                scheduler,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);
        shortForm.register(commands.buildWarpLock());
        shortForm.register(commands.buildWarpUnlock());

        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        shortForm.execute("warplock shop", source);
        shortForm.execute("warpunlock shop", source);

        verify(warps).setWarpLock(any(), eq(PROFILE), eq(WarpName.of("shop")), eq(true));
        verify(warps).setWarpLock(any(), eq(PROFILE), eq(WarpName.of("shop")), eq(false));
    }

    @Test
    @DisplayName("Moving a warp between directory categories reaches the other setter nobody called")
    void movingACategoryReachesTheSetter() throws Exception {
        run("warp category shop SHOPS", player);

        verify(warps).setWarpCategory(any(), eq(PROFILE), eq(WarpName.of("shop")), eq(WarpCategory.SHOPS));
    }

    @Test
    @DisplayName("A category nobody publishes never reaches the setter")
    void anUnknownCategoryNeverReachesTheSetter() throws Exception {
        run("warp category shop GRAVITY", player);

        verify(warps, never()).setWarpCategory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A role that may not touch a warp is told so rather than the command failing silently")
    void aRefusedLockIsAnAnswer() throws Exception {
        doThrow(new SecurityException("no")).when(warps).setWarpLock(any(), any(), any(), anyBoolean());

        run("warp lock shop", player);

        assertThat(player.nextMessage()).isNotNull();
    }
}
