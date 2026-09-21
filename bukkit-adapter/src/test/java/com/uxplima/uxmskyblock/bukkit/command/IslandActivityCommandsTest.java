package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
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
 * {@code /is activity}, end to end through Brigadier.
 *
 * <p>The feed, its table and its service were here since the enterprise foundation work and nothing
 * ever read one. The island the feed is asked for is the part worth pinning: an instance id that
 * does not match the one the writer used is a log that is always empty and never wrong.
 */
class IslandActivityCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private ActivityFeedService feed;
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

    private static ActivityEvent eventAt(Instant when) {
        return new ActivityEvent(
                UUID.randomUUID(),
                ISLAND.value().toString(),
                PROFILE,
                ActivityEventType.BANK_DEPOSIT,
                ActivityVisibility.MEMBERS_ONLY,
                "bank.deposit",
                1,
                "{\"amount\":250}",
                when);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        feed = mock(ActivityFeedService.class);
        when(feed.getRecentActivities(anyString(), anyInt())).thenReturn(List.of());

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandActivityCommands commands = new IslandActivityCommands(
                () -> feed,
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
    @DisplayName("The feed is asked for the caller's own island, by the id the writer uses")
    void theFeedIsAskedForTheCallersIsland() throws Exception {
        run("activity", player);

        verify(feed).getRecentActivities(eq(ISLAND.value().toString()), anyInt());
    }

    @Test
    @DisplayName("An empty feed is an answer rather than a silence")
    void anEmptyFeedIsAnAnswer() throws Exception {
        run("activity", player);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Every entry the feed holds is put in front of the player")
    void everyEntryIsShown() throws Exception {
        Instant now = Instant.now();
        when(feed.getRecentActivities(anyString(), anyInt()))
                .thenReturn(List.of(
                        eventAt(now.minus(2, ChronoUnit.MINUTES)),
                        eventAt(now.minus(5, ChronoUnit.HOURS)),
                        eventAt(now.minus(3, ChronoUnit.DAYS))));

        run("activity", player);

        // A header and three rows.
        for (int line = 0; line < 4; line++) {
            assertThat(player.nextMessage())
                    .describedAs("line %d of the feed", line)
                    .isNotNull();
        }
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("A player with no island never asks the feed for one")
    void noIslandNeverReachesTheFeed() throws Exception {
        PlayerMock stranger = server.addPlayer();

        run("activity", stranger);

        verify(feed, never()).getRecentActivities(anyString(), anyInt());
    }

    @Test
    @DisplayName("The console is told to be a player rather than reading nobody's feed")
    void theConsoleIsRefused() throws Exception {
        run("activity", server.getConsoleSender());

        verify(feed, never()).getRecentActivities(anyString(), anyInt());
    }
}
