package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
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
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

/**
 * {@code /is home}, {@code /is go}, {@code /is visit}, {@code /is setspawn}, {@code /is nether} and
 * {@code /is end} run, end to end, through Brigadier.
 *
 * <p>{@code /is visit} is the one worth the trouble. It asked nothing before being put down: not the
 * island's ban list, not the LOCKED flag the warp path has always honoured, and not VISITOR_ACCESS,
 * which the settings form offers and nothing on the server read. An island archived for inactivity
 * is locked by the inactivity service, and it was open to anybody who knew the owner's name.
 */
class IslandNavigationCommandsTest {

    private static final String ISLAND_WORLD = "skyblock_world";
    private static final IslandId TARGET_ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId VISITOR = new ProfileId(UUID.randomUUID());
    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private World islandWorld;
    private World lobby;
    private IslandLocationService locations;
    private IslandWarpService warps;
    private IslandAllianceService alliances;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private boolean teleportAttempted;

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

    private static Island islandWith(IslandFlags flags) {
        Island base = Island.create(
                TARGET_ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                OWNER,
                Instant.now());
        return base.withFlags(flags);
    }

    private static IslandLocation locationOf(IslandId islandId) {
        return new IslandLocation(
                islandId, ISLAND_WORLD, IslandBounds.fromCenterAndRadius(0, 0, 50), 8.5, 65.0, 8.5, 0.0f, 0.0f);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        lobby = server.addSimpleWorld("lobby");
        islandWorld = server.addSimpleWorld(ISLAND_WORLD);
        player = server.addPlayer();
        player.teleport(new Location(lobby, 0.0, 64.0, 0.0));
        teleportAttempted = false;

        locations = mock(IslandLocationService.class);
        when(locations.findIsland(TARGET_ISLAND)).thenReturn(Optional.of(islandWith(IslandFlags.defaults())));
        when(locations.findLocation(TARGET_ISLAND)).thenReturn(Optional.of(locationOf(TARGET_ISLAND)));
        when(locations.resolveHome(VISITOR)).thenReturn(Optional.of(locationOf(TARGET_ISLAND)));
        when(locations.findIslandId(VISITOR)).thenReturn(Optional.empty());

        warps = mock(IslandWarpService.class);
        when(warps.isPlayerBanned(any(), any())).thenReturn(false);

        alliances = mock(IslandAllianceService.class);

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(VISITOR));

        IslandNavigationCommands commands = new IslandNavigationCommands(
                locations,
                sessions,
                inlineScheduler(),
                ISLAND_WORLD,
                () -> null,
                () -> null,
                () -> warps,
                () -> alliances,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildHome());
        dispatcher.register(commands.buildGo());
        dispatcher.register(commands.buildVisit());
        dispatcher.register(commands.buildNether());
        dispatcher.register(commands.buildEnd());
        dispatcher.register(commands.buildSetSpawn());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Runs the line and records whether the command got as far as putting the player down.
     *
     * <p>MockBukkit does not implement {@code teleportAsync}. It raises an exception JUnit reads as
     * an assumption failure, which turns the test into a silent skip rather than a failure: the
     * first draft of this class had eight of its fourteen tests disappear that way and report
     * green. The teleport is caught here and turned into a fact to assert on, so a refusal that
     * stopped being a refusal fails rather than vanishing.
     */
    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        try {
            dispatcher.execute(line, source);
        } catch (UnimplementedOperationException unimplemented) {
            assertThat(unimplemented.getStackTrace())
                    .describedAs("only the teleport is allowed to be unimplemented here")
                    .anyMatch(frame -> frame.getMethodName().contains("teleport"));
            teleportAttempted = true;
        }
    }

    private String visitLine() {
        return "visit " + TARGET_ISLAND.value();
    }

    @Test
    @DisplayName("An open island takes the visitor")
    void anOpenIslandTakesTheVisitor() throws Exception {
        run(visitLine(), player);

        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("A locked island leaves the visitor where they stood")
    void aLockedIslandRefusesTheVisit() throws Exception {
        when(locations.findIsland(TARGET_ISLAND))
                .thenReturn(Optional.of(islandWith(IslandFlags.defaults().withFlag(IslandFlags.LOCKED, true))));

        run(visitLine(), player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("An island closed to visitors leaves the visitor where they stood")
    void visitorAccessOffRefusesTheVisit() throws Exception {
        when(locations.findIsland(TARGET_ISLAND))
                .thenReturn(
                        Optional.of(islandWith(IslandFlags.defaults().withFlag(IslandFlags.VISITOR_ACCESS, false))));

        run(visitLine(), player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A banned player is refused by an island that is open to everybody else")
    void aBannedPlayerIsRefused() throws Exception {
        when(warps.isPlayerBanned(eq(TARGET_ISLAND), any(PlayerUuid.class))).thenReturn(true);

        run(visitLine(), player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("A member of the island walks through its own lock")
    void aMemberWalksThroughTheLock() throws Exception {
        when(locations.findIsland(TARGET_ISLAND))
                .thenReturn(Optional.of(islandWith(IslandFlags.defaults().withFlag(IslandFlags.LOCKED, true))));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(OWNER));
        when(locations.findIslandId(OWNER)).thenReturn(Optional.of(TARGET_ISLAND));

        IslandNavigationCommands ownerCommands = new IslandNavigationCommands(
                locations,
                sessions,
                inlineScheduler(),
                ISLAND_WORLD,
                () -> null,
                () -> null,
                () -> warps,
                () -> alliances,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));
        CommandDispatcher<CommandSourceStack> ownerDispatcher = new CommandDispatcher<>();
        ownerDispatcher.register(ownerCommands.buildVisit());

        dispatcher = ownerDispatcher;
        run(visitLine(), player);

        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("The visit never asks who the ban list names before it knows which island")
    void theBanListIsAskedForTheTargetIsland() throws Exception {
        run(visitLine(), player);

        verify(warps).isPlayerBanned(eq(TARGET_ISLAND), any(PlayerUuid.class));
    }

    @Test
    @DisplayName("/is home puts the player on their own island")
    void homePutsThePlayerOnTheirIsland() throws Exception {
        run("home", player);

        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("/is go is the same command as /is home")
    void goIsTheSameAsHome() throws Exception {
        run("go", player);

        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("A player with no island is told so rather than being teleported into nothing")
    void noIslandIsNotATeleport() throws Exception {
        when(locations.resolveHome(VISITOR)).thenReturn(Optional.empty());

        run("home", player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("/is setspawn writes the spot the player is standing on, not the island centre")
    void setSpawnWritesWhereThePlayerStands() throws Exception {
        player.teleport(new Location(islandWorld, 12.5, 70.0, -8.5, 90.0f, 10.0f));

        run("setspawn", player);

        verify(locations)
                .updateSpawn(eq(VISITOR), eq(ISLAND_WORLD), eq(12.5), eq(70.0), eq(-8.5), eq(90.0f), eq(10.0f));
    }

    @Test
    @DisplayName("Dimension travel that an operator switched off says so rather than throwing")
    void dimensionsOffIsAnAnswer() throws Exception {
        run("nether", player);
        run("end", player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("The console is told to be a player rather than being teleported")
    void theConsoleIsRefused() throws Exception {
        run("home", server.getConsoleSender());

        verify(locations, org.mockito.Mockito.never()).resolveHome(any());
    }

    @Test
    @DisplayName("A target that names no island is refused without a lookup of its flags")
    void anUnresolvedTargetNeverReachesTheRule() throws Exception {
        when(locations.findIsland(any())).thenReturn(Optional.empty());

        run(visitLine(), player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("Nothing about the visit depends on the spawn writer")
    void theVisitDoesNotWriteSpawn() throws Exception {
        run(visitLine(), player);

        verify(locations, org.mockito.Mockito.never())
                .updateSpawn(any(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
    }
}
