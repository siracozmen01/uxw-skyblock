package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is season}, end to end through Brigadier.
 *
 * <p>The season service, its snapshots, its payouts and its storage were here since the season work
 * and no command reached any of it. A player who won a season was owed a reward that stayed owed.
 */
class IslandSeasonCommandsTest {

    private static final SeasonId SEASON = SeasonId.of(3);

    private ServerMock server;
    private PlayerMock player;
    private IslandSeasonService seasons;
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
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any(Runnable.class));
        return scheduler;
    }

    private static SeasonRecord record(SeasonId id, SeasonState state) {
        return new SeasonRecord(id, "Season " + id.number(), Instant.now().minusSeconds(86_400), Instant.now(), state);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("Owner");

        seasons = mock(IslandSeasonService.class);
        when(seasons.activeSeason()).thenReturn(Optional.of(record(SEASON, SeasonState.ACTIVE)));
        when(seasons.season(any())).thenReturn(Optional.empty());
        when(seasons.season(SEASON)).thenReturn(Optional.of(record(SEASON, SeasonState.ACTIVE)));
        when(seasons.seasons()).thenReturn(List.of(record(SEASON, SeasonState.ACTIVE)));
        when(seasons.standings(any(), any(), anyInt())).thenReturn(List.of());
        when(seasons.dispatchPendingPayouts(any(), any())).thenReturn(0);

        IslandSeasonCommands commands = new IslandSeasonCommands(
                () -> seasons,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildSeason());
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
    @DisplayName("A bare /is season says which one is running")
    void bareSeasonNamesTheRunningOne() throws Exception {
        run("season", player);

        verify(seasons).activeSeason();
    }

    @Test
    @DisplayName("/is season list reads every season the server has run")
    void listReadsEverySeason() throws Exception {
        run("season list", player);

        verify(seasons).seasons();
    }

    @Test
    @DisplayName("/is season top with no number means the season that is running")
    void topWithNoNumberMeansTheRunningOne() throws Exception {
        run("season top", player);

        verify(seasons).activeSeason();
        verify(seasons).standings(eq(SEASON), eq(SeasonMetric.LEVEL), anyInt());
    }

    @Test
    @DisplayName("/is season top names the season the player typed")
    void topNamesTheSeasonTyped() throws Exception {
        run("season top 3", player);

        verify(seasons).season(SEASON);
        verify(seasons).standings(eq(SEASON), eq(SeasonMetric.LEVEL), anyInt());
    }

    @Test
    @DisplayName("Every metric the plugin publishes is a word the command accepts")
    void everyPublishedMetricIsAccepted() throws Exception {
        for (SeasonMetric metric : SeasonMetric.values()) {
            run("season top 3 " + metric.name().toLowerCase(java.util.Locale.ROOT), player);
        }

        for (SeasonMetric metric : SeasonMetric.values()) {
            verify(seasons).standings(eq(SEASON), eq(metric), anyInt());
        }
    }

    @Test
    @DisplayName("A metric nobody publishes reads no standings rather than falling back to one")
    void anUnknownMetricReadsNothing() throws Exception {
        run("season top 3 popularity", player);

        verify(seasons, never()).standings(any(), any(), anyInt());
    }

    @Test
    @DisplayName("A season the server never ran reads no standings")
    void anUnknownSeasonReadsNothing() throws Exception {
        run("season top 99", player);

        verify(seasons).season(SeasonId.of(99));
        verify(seasons, never()).standings(any(), any(), anyInt());
    }

    @Test
    @DisplayName("A season number of zero is refused by the argument type, not by the service")
    void seasonZeroIsRefusedByTheType() {
        for (String number : new String[] {"0", "-1"}) {
            assertThatThrownBy(() -> run("season top " + number, player))
                    .describedAs("season %s must be refused by the argument type", number)
                    .isInstanceOf(Exception.class);
        }
        verify(seasons, never()).standings(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Standings are put in front of the caller, one line each")
    void standingsAreShownOneLineEach() throws Exception {
        when(seasons.standings(any(), any(), anyInt()))
                .thenReturn(List.of(
                        new SeasonSnapshotEntry(
                                SEASON,
                                SeasonMetric.LEVEL,
                                1,
                                IslandId.of(UUID.randomUUID()),
                                new PlayerUuid(player.getUniqueId()),
                                1200L,
                                Instant.now()),
                        new SeasonSnapshotEntry(
                                SEASON,
                                SeasonMetric.LEVEL,
                                2,
                                IslandId.of(UUID.randomUUID()),
                                new PlayerUuid(UUID.randomUUID()),
                                900L,
                                Instant.now())));

        run("season top 3", player);

        // A heading and two rows.
        for (int line = 0; line < 3; line++) {
            assertThat(player.nextMessage()).describedAs("line %d", line).isNotNull();
        }
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("/is season rewards hands over what the last season owed, which nothing could before")
    void rewardsCollectWhatIsOwed() throws Exception {
        when(seasons.dispatchPendingPayouts(any(), any())).thenReturn(2);

        run("season rewards", player);

        verify(seasons).dispatchPendingPayouts(eq(new PlayerUuid(player.getUniqueId())), any());
    }

    @Test
    @DisplayName("The console may read a season, because reading one belongs to nobody in particular")
    void theConsoleMayReadASeason() throws Exception {
        run("season", server.getConsoleSender());

        verify(seasons).activeSeason();
    }

    @Test
    @DisplayName("The console may not collect a reward, because a reward belongs to a player")
    void theConsoleMayNotCollectAReward() throws Exception {
        run("season rewards", server.getConsoleSender());

        verify(seasons, never()).dispatchPendingPayouts(any(), any());
    }
}
