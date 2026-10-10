package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
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

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeId;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
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
 * {@code /is sethome}, {@code /is homes}, {@code /is delhome} and {@code /is gohome}, end to end.
 *
 * <p>Where the player stands is read on the thread that owns them and carried into the scheduler.
 * On Folia a location read from the async pool throws rather than lagging, so the test that matters
 * most here is the one saying the home lands where the player was.
 */
class IslandHomeCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String WORLD = "skyblock_world";

    private ServerMock server;
    private PlayerMock player;
    private HomeService homes;
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

    private static Home homeAt(String name, double x, double y, double z) {
        return new Home(
                HomeId.random(),
                PROFILE,
                ISLAND,
                name,
                HomeScope.PERSONAL,
                WORLD,
                x,
                y,
                z,
                0.0f,
                0.0f,
                Instant.now(),
                Instant.now());
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        World world = server.addSimpleWorld(WORLD);
        player = server.addPlayer();
        player.teleport(new Location(world, 0.0, 64.0, 0.0));
        teleportAttempted = false;

        homes = mock(HomeService.class);
        when(homes.setHome(
                        any(),
                        any(),
                        anyString(),
                        any(HomeScope.class),
                        anyString(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyFloat(),
                        anyFloat(),
                        anyInt()))
                .thenReturn(new HomeService.SetHomeResult.Success(homeAt("base", 1.0, 2.0, 3.0)));
        when(homes.listHomes(PROFILE)).thenReturn(List.of());
        when(homes.deleteHome(eq(PROFILE), anyString())).thenReturn(true);
        when(homes.getHome(eq(PROFILE), anyString())).thenReturn(Optional.empty());

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        // The player's island, around the spot they stand on: a home must stand on it.
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(com.uxplima.uxmskyblock.core.domain.island.IslandLocation.fromCenterAndRadius(
                        ISLAND, WORLD, 0, 0, 50)));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        this.locations = locations;
        this.sessions = sessions;
        rebuildWith(HomeConfiguration.defaults());
    }

    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;

    /** The commands as a server with this configuration builds them. */
    private void rebuildWith(HomeConfiguration configuration) {
        java.util.function.Function<ProfileId, com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType> modes =
                commands == null ? null : currentModes;
        commands = new IslandHomeCommands(
                () -> homes,
                locations,
                inlineScheduler(),
                configuration,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildSetHome());
        dispatcher.register(commands.buildNamedHome());
        dispatcher.register(commands.buildDeleteHome());
        dispatcher.register(commands.buildTravelHome());
        if (modes != null) {
            commands.useGameModes(modes);
        }
    }

    private java.util.function.Function<ProfileId, com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType>
            currentModes = profile -> com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType.SKYBLOCK;

    private IslandHomeCommands commands;

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** MockBukkit does not implement teleportAsync, so the teleport is a fact rather than a skip. */
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
    @DisplayName("A home lands where the player is standing, not where the island is")
    void aHomeLandsWhereThePlayerStands() throws Exception {
        player.teleport(new Location(server.getWorld(WORLD), 12.5, 70.0, -8.5, 90.0f, 10.0f));

        run("sethome base", player);

        verify(homes)
                .setHome(
                        eq(PROFILE),
                        eq(ISLAND),
                        eq("base"),
                        eq(HomeScope.PERSONAL),
                        eq(WORLD),
                        eq(12.5),
                        eq(70.0),
                        eq(-8.5),
                        eq(90.0f),
                        eq(10.0f),
                        anyInt());
    }

    @Test
    @DisplayName("A home off the player's own island is refused and nothing is written")
    void aHomeOffTheIslandIsRefused() throws Exception {
        player.teleport(new Location(server.getWorld(WORLD), 500.5, 70.0, 500.5));

        run("sethome far", player);

        verify(homes, org.mockito.Mockito.never())
                .setHome(
                        any(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyFloat(),
                        anyFloat(),
                        anyInt());
        assertThat(said()).contains("home.outside_island");
    }

    @Test
    @DisplayName("A game mode that keeps no homes refuses one, even on the island")
    void aModeWithoutHomesRefusesOne() throws Exception {
        currentModes = profile -> com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType.ONEBLOCK;
        rebuildWith(new HomeConfiguration(
                true,
                1,
                10,
                java.util.Map.of(),
                new com.uxplima.uxmskyblock.core.domain.home.HomePlacementPolicy(
                        com.uxplima.uxmskyblock.core.domain.home.HomePlacementPolicy.shipped()
                                .dimensions(),
                        java.util.Set.of(com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType.ONEBLOCK))));

        run("sethome base", player);

        verify(homes, org.mockito.Mockito.never())
                .setHome(
                        any(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyFloat(),
                        anyFloat(),
                        anyInt());
        assertThat(said()).contains("home.mode_disabled");
    }

    /** Everything the player was told, in plain text. */
    private java.util.List<String> said() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        net.kyori.adventure.text.Component next;
        while ((next = player.nextComponentMessage()) != null) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(next);
            lines.add(plain.substring(plain.indexOf('»') + 1).trim());
        }
        return lines;
    }

    @Test
    @DisplayName("The allowance the service is given is the one the configuration works out")
    void theAllowanceComesFromTheConfiguration() throws Exception {
        run("sethome base", player);

        verify(homes)
                .setHome(
                        any(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyFloat(),
                        anyFloat(),
                        eq(HomeConfiguration.defaults().allowanceFor(node -> false)));
    }

    @Test
    @DisplayName("/is homes lists the caller's own homes")
    void listingReadsTheCallersHomes() throws Exception {
        run("homes", player);

        verify(homes).listHomes(PROFILE);
    }

    @Test
    @DisplayName("/is homes shows the homes in their window, and writes nothing in chat")
    void listingOpensTheWindow() throws Exception {
        List<Home> held = List.of(homeAt("base", 1.0, 2.0, 3.0));
        when(homes.listHomes(PROFILE)).thenReturn(held);
        com.uxplima.uxmskyblock.bukkit.menu.HomeList window = mock(com.uxplima.uxmskyblock.bukkit.menu.HomeList.class);
        when(window.show(player, held, HomeConfiguration.defaults().allowanceFor(node -> false)))
                .thenReturn(true);
        commands.useHomeList(() -> window);

        run("homes", player);

        verify(window).show(player, held, HomeConfiguration.defaults().allowanceFor(node -> false));
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("/is homes on a server whose operator removed the window lists the homes in chat")
    void listingWithoutTheWindowIsChat() throws Exception {
        when(homes.listHomes(PROFILE)).thenReturn(List.of(homeAt("base", 1.0, 2.0, 3.0)));
        com.uxplima.uxmskyblock.bukkit.menu.HomeList window = mock(com.uxplima.uxmskyblock.bukkit.menu.HomeList.class);
        commands.useHomeList(() -> window);

        run("homes", player);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("/is delhome names the home the player typed")
    void deletingNamesTheHome() throws Exception {
        run("delhome base", player);

        verify(homes).deleteHome(PROFILE, "base");
    }

    @Test
    @DisplayName("Deleting a home nobody made is an answer, not a silence")
    void deletingAnUnknownHomeIsAnAnswer() throws Exception {
        when(homes.deleteHome(eq(PROFILE), anyString())).thenReturn(false);

        run("delhome nowhere", player);

        verify(homes).deleteHome(PROFILE, "nowhere");
        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Travelling to a home nobody made moves nobody")
    void travellingToAnUnknownHomeMovesNobody() throws Exception {
        run("gohome nowhere", player);

        verify(homes).getHome(PROFILE, "nowhere");
        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("Travelling to a home that exists puts the player down")
    void travellingToAKnownHomeMovesThePlayer() throws Exception {
        when(homes.getHome(PROFILE, "base")).thenReturn(Optional.of(homeAt("base", 20.5, 65.0, -3.5)));

        run("gohome base", player);

        assertThat(teleportAttempted).isTrue();
    }

    @Test
    @DisplayName("A home in a world that is not loaded is an answer, not a teleport into nothing")
    void anUnloadedWorldIsAnAnswer() throws Exception {
        Home elsewhere = new Home(
                HomeId.random(),
                PROFILE,
                ISLAND,
                "far",
                HomeScope.PERSONAL,
                "a_world_nobody_loaded",
                0.0,
                64.0,
                0.0,
                0.0f,
                0.0f,
                Instant.now(),
                Instant.now());
        when(homes.getHome(PROFILE, "far")).thenReturn(Optional.of(elsewhere));

        run("gohome far", player);

        assertThat(teleportAttempted).isFalse();
    }

    @Test
    @DisplayName("Every home verb needs a name, rather than guessing one")
    void everyVerbNeedsAName() {
        for (String line : new String[] {"sethome", "delhome", "gohome"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(line, player))
                    .describedAs("%s must be refused without a name", line)
                    .isInstanceOf(Exception.class);
        }
        verify(homes, never())
                .setHome(
                        any(),
                        any(),
                        anyString(),
                        any(),
                        anyString(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyFloat(),
                        anyFloat(),
                        anyInt());
        verify(homes, never()).deleteHome(any(), anyString());
    }

    @Test
    @DisplayName("The console is told to be a player rather than saving a home in nowhere")
    void theConsoleIsRefused() throws Exception {
        run("homes", server.getConsoleSender());

        verify(homes, never()).listHomes(any());
    }
}
