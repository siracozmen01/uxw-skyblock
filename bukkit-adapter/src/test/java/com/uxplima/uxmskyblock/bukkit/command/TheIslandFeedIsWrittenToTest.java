package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.message.MessagePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An island's feed has something in it, and it reads in the reader's own language.
 *
 * <p>The feed, its table, its twelve event types and {@code /is activity} have been here since the
 * activity work, and nothing ever wrote a row: recordActivity had no caller anywhere, so every
 * island's feed was empty for as long as the server ran.
 */
class TheIslandFeedIsWrittenToTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private ProfileId profileId;
    private InMemoryFeed storage;
    private ActivityFeedService feed;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("Owner");
        profileId = new ProfileId(player.getUniqueId());

        storage = new InMemoryFeed();
        feed = new ActivityFeedService(storage);

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

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(profileId)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));

        IslandActivityCommands commands =
                new IslandActivityCommands(() -> feed, locations, scheduler, Messages.bundled(), sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn((CommandSender) player);
        dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("A line names a message and its values, never a sentence")
    void alineNamesAMessage() {
        ActivityEvent written = feed.record(
                ISLAND.value().toString(),
                profileId,
                ActivityEventType.MEMBER_JOINED,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.member_joined",
                Map.of("player", "Guest"));

        assertThat(written.payloadTypeId()).isEqualTo("activity.member_joined");
        assertThat(written.payloadData())
                .describedAs("the values, and not one word a player reads")
                .doesNotContain("joined");
        assertThat(MessagePayload.unpack(written.payloadData())).containsEntry("player", "Guest");
    }

    @Test
    @DisplayName("The feed reads back with its values filled in and how long ago it was")
    void thefeedReadsBackFilledIn() throws Exception {
        feed.record(
                ISLAND.value().toString(),
                profileId,
                ActivityEventType.BANK_DEPOSIT,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.bank_deposit",
                Map.of("player", "Owner", "amount", "500"));

        run("activity");

        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage())
                .describedAs("the sentence comes out of the catalogue with the values filled in")
                .contains("Owner")
                .contains("500")
                .contains("ago");
    }

    @Test
    @DisplayName("A line naming a message the catalogue does not have still reads")
    void anunknownMessageStillReads() throws Exception {
        feed.recordActivity(
                ISLAND.value().toString(),
                profileId,
                ActivityEventType.TEMPLATE_APPLIED,
                ActivityVisibility.MEMBERS_ONLY,
                "uxm:something_older",
                1,
                "a template was applied");

        run("activity");

        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage()).contains("a template was applied");
    }

    @Test
    @DisplayName("An island where nothing has happened says so")
    void anemptyFeedSaysSo() throws Exception {
        run("activity");

        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("A line older than the retention is dropped, and a recent one is kept")
    void anoldLineIsDropped() {
        storage.append(oldEvent(Instant.now().minusSeconds(60 * 60 * 24 * 40)));
        feed.record(
                ISLAND.value().toString(),
                profileId,
                ActivityEventType.MEMBER_JOINED,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.member_joined",
                Map.of("player", "Guest"));

        assertThat(feed.purgeOlderThan(Instant.now().minusSeconds(60 * 60 * 24 * 30)))
                .describedAs("the one from forty days ago")
                .isEqualTo(1);
        assertThat(storage.events).hasSize(1);
    }

    private ActivityEvent oldEvent(Instant when) {
        return new ActivityEvent(
                UUID.randomUUID(),
                ISLAND.value().toString(),
                profileId,
                ActivityEventType.MEMBER_JOINED,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.member_joined",
                1,
                "player\u001fOld",
                when);
    }

    /** The smallest feed that behaves like the real one. */
    private static final class InMemoryFeed implements ActivityFeedStoragePort {

        final List<ActivityEvent> events = new ArrayList<>();

        void append(ActivityEvent event) {
            events.add(event);
        }

        @Override
        public void appendEvent(ActivityEvent event) {
            events.add(event);
        }

        @Override
        public List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit) {
            return events.stream()
                    .filter(event -> event.instanceId().equals(instanceId))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int purgeEventsBefore(Instant before) {
            int held = events.size();
            events.removeIf(event -> event.createdAt().isBefore(before));
            return held - events.size();
        }
    }
}
