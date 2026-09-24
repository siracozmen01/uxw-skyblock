package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
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
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is flag} runs, end to end, through Brigadier.
 *
 * <p>Thirteen command classes shipped with no test of any kind. Every one of them is what a player
 * actually touches: a wrong argument type, a branch that reads the wrong island, a handler that
 * never reaches its service, none of that shows up in a compile and none of it showed up in a test
 * either. This runs the real builder through a real dispatcher and watches what the handler does.
 */
class IslandFlagCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandFlagService flagService;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    /** Runs scheduled work inline, so a command finishes before the assertion reads its effect. */
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
        player = server.addPlayer();

        flagService = mock(IslandFlagService.class);
        when(flagService.flagsOf(any())).thenReturn(Map.of("PVP", false, "LOCKED", true));
        when(flagService.flagNames(any())).thenReturn("locked, pvp");

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island()));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandFlagCommands commands = new IslandFlagCommands(
                flagService,
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

    private int run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("A Turkish reader sees a flag by its name, and the list keeps the key to type")
    void aFlagIsNamedForTheReader() throws Exception {
        player.setLocale(java.util.Locale.forLanguageTag("tr"));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island()));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(
                new IslandFlagCommands(flagService, locations, inlineScheduler(), Messages.bundled(), sessions)
                        .build());
        when(flagService.toggle(any(), eq(PROFILE), eq("pvp")))
                .thenReturn(new IslandFlagService.FlagChange.Changed("pvp", true));

        run("flag pvp", player);
        assertThat(player.nextMessage()).contains("PvP Savaşı").doesNotContain("pvp");

        run("flag", player);
        java.util.List<String> listed = new java.util.ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            listed.add(line);
        }
        assertThat(listed).anyMatch(line -> line.contains("PvP Savaşı") && line.contains("(pvp)"));
    }

    @Test
    @DisplayName("Every flag an island has is named in English and in Turkish")
    void everyFlagIsNamed() {
        MessageProvider provider = Messages.bundled().provider();
        for (String flag : com.uxplima.uxmskyblock.core.domain.island.IslandFlags.defaults()
                .values()
                .keySet()) {
            String key = "flag.names." + flag.toLowerCase(java.util.Locale.ROOT);
            assertThat(provider.getKeys("en")).describedAs("en").contains(key);
            assertThat(provider.getKeys("tr")).describedAs("tr").contains(key);
        }
    }

    @Test
    @DisplayName("Naming a flag turns it the other way")
    void namingAFlagTogglesIt() throws Exception {
        when(flagService.toggle(any(), eq(PROFILE), eq("pvp")))
                .thenReturn(new IslandFlagService.FlagChange.Changed("pvp", true));

        run("flag pvp", player);

        verify(flagService).toggle(any(), eq(PROFILE), eq("pvp"));
    }

    @Test
    @DisplayName("Naming no flag lists them and changes nothing")
    void namingNoFlagListsThem() throws Exception {
        run("flag", player);

        verify(flagService).flagsOf(any());
        verify(flagService, never()).toggle(any(), any(), any());
    }

    @Test
    @DisplayName("The explicit list branch reaches the same place")
    void theListBranchLists() throws Exception {
        run("flag list", player);

        verify(flagService).flagsOf(any());
        verify(flagService, never()).toggle(any(), any(), any());
    }

    @Test
    @DisplayName("The console is told this needs a player, and no flag moves")
    void theConsoleIsRefused() throws Exception {
        run("flag pvp", server.getConsoleSender());

        verify(flagService, never()).toggle(any(), any(), any());
    }

    @Test
    @DisplayName("A player with no session touches no flag")
    void aPlayerWithNoSessionTouchesNothing() throws Exception {
        PlayerMock stranger = server.addPlayer();

        run("flag pvp", stranger);

        verify(flagService, never()).toggle(any(), any(), any());
    }

    @Test
    @DisplayName("Every answer the service can give is handled, so none of them is silence")
    void everyAnswerIsHandled() throws Exception {
        for (IslandFlagService.FlagChange answer : java.util.List.of(
                new IslandFlagService.FlagChange.Changed("pvp", false),
                new IslandFlagService.FlagChange.UnknownFlag("nope", "locked, pvp"),
                new IslandFlagService.FlagChange.NotAllowed(),
                new IslandFlagService.FlagChange.IslandMissing())) {
            when(flagService.toggle(any(), any(), any())).thenReturn(answer);
            player.nextMessage();

            run("flag pvp", player);

            assertThat(player.nextMessage())
                    .describedAs("a click that answers nothing is how a player learns to stop trying: %s", answer)
                    .isNotNull();
        }
    }
}
