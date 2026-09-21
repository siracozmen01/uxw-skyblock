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
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is info}, end to end through Brigadier.
 *
 * <p>The design specification publishes it and nothing answered it. Everything it shows was already
 * readable through five separate commands, which is five things to type when a player wants one
 * answer.
 */
class IslandInfoCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandLocationService locations;
    private IslandNameService names;
    private IslandBoosterService boosters;
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

        island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                new PlayerUuid(player.getUniqueId()),
                OWNER,
                Instant.now());

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(OWNER)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenAnswer(invocation -> Optional.of(island));

        names = mock(IslandNameService.class);
        when(names.getIslandName(ISLAND)).thenReturn(Optional.of(IslandName.of("The Rock")));

        boosters = mock(IslandBoosterService.class);
        when(boosters.getActiveBoosters(eq(ISLAND), any(Instant.class))).thenReturn(List.of());

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(OWNER));

        IslandInfoCommands commands = new IslandInfoCommands(
                locations,
                () -> names,
                () -> boosters,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildInfo());
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

    private String allLines() {
        StringBuilder out = new StringBuilder();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("The island's own name is read, rather than its id shown to a player who named it")
    void theIslandsNameIsRead() throws Exception {
        run("info", player);

        verify(names).getIslandName(ISLAND);
    }

    @Test
    @DisplayName("An island nobody named still answers, with its id where the name would be")
    void anUnnamedIslandStillAnswers() throws Exception {
        when(names.getIslandName(ISLAND)).thenReturn(Optional.empty());

        run("info", player);

        assertThat(allLines()).describedAs("the screen was drawn").isNotEmpty();
    }

    @Test
    @DisplayName("The whole screen is one block of lines, not one line per command")
    void theWholeScreenIsOneBlock() throws Exception {
        run("info", player);

        // A heading, the owner, the members, the state, the visitors line, and the booster answer.
        assertThat(allLines().lines().count()).isEqualTo(6);
    }

    @Test
    @DisplayName("The three visitor states are three catalogue keys, not one line with a branch in it")
    void theThreeVisitorStatesAreThreeKeys() {
        Island open = island;
        Island closed = island.withFlags(island.flags().withFlag(IslandFlags.VISITOR_ACCESS, false));
        Island locked = island.withFlags(island.flags().withFlag(IslandFlags.LOCKED, true));

        assertThat(IslandInfoCommands.accessKeyOf(open)).isEqualTo("info.access_open");
        assertThat(IslandInfoCommands.accessKeyOf(closed)).isEqualTo("info.access_closed");
        assertThat(IslandInfoCommands.accessKeyOf(locked)).isEqualTo("info.access_locked");
    }

    @Test
    @DisplayName("A locked island reads as locked even when visitor access is on, because the lock wins")
    void theLockWinsOverVisitorAccess() {
        Island lockedAndOpen = island.withFlags(
                island.flags().withFlag(IslandFlags.LOCKED, true).withFlag(IslandFlags.VISITOR_ACCESS, true));

        assertThat(IslandInfoCommands.accessKeyOf(lockedAndOpen)).isEqualTo("info.access_locked");
    }

    @Test
    @DisplayName("A running booster is asked for, which is what the document asks this command for")
    void aRunningBoosterIsAskedFor() throws Exception {
        when(boosters.getActiveBoosters(eq(ISLAND), any(Instant.class)))
                .thenReturn(List.of(new IslandBooster(
                        UUID.randomUUID(),
                        ISLAND,
                        BoosterCategory.SPAWNER_RATE,
                        2.5,
                        Instant.now().plusSeconds(3600),
                        Instant.now(),
                        null,
                        3600L)));

        run("info", player);

        verify(boosters).getActiveBoosters(eq(ISLAND), any(Instant.class));
        // The five island lines, a heading for the boosters, and one line for the booster itself.
        assertThat(allLines().lines().count()).isEqualTo(7);
    }

    @Test
    @DisplayName("Two running boosters are two lines, so one never hides the other")
    void twoBoostersAreTwoLines() throws Exception {
        when(boosters.getActiveBoosters(eq(ISLAND), any(Instant.class)))
                .thenReturn(List.of(
                        new IslandBooster(
                                UUID.randomUUID(),
                                ISLAND,
                                BoosterCategory.SPAWNER_RATE,
                                2.5,
                                Instant.now().plusSeconds(3600),
                                Instant.now(),
                                null,
                                3600L),
                        new IslandBooster(
                                UUID.randomUUID(),
                                ISLAND,
                                BoosterCategory.CROP_GROWTH,
                                2.0,
                                Instant.now().plusSeconds(60),
                                Instant.now(),
                                null,
                                60L)));

        run("info", player);

        assertThat(allLines().lines().count()).isEqualTo(8);
    }

    @Test
    @DisplayName("A player with no island is told so, and nothing else is read")
    void noIslandIsTheWholeAnswer() throws Exception {
        when(locations.findIslandId(OWNER)).thenReturn(Optional.empty());

        run("info", player);

        verify(boosters, never()).getActiveBoosters(any(), any());
        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("The console is told to be a player rather than reading nobody's island")
    void theConsoleIsRefused() throws Exception {
        run("info", server.getConsoleSender());

        verify(locations, never()).findIslandId(any());
    }
}
