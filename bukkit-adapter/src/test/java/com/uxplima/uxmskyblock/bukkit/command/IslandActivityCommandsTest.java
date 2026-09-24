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
    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;

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
                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(java.util.Map.of("amount", "250")),
                when);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        feed = mock(ActivityFeedService.class);
        when(feed.getRecentActivities(anyString(), anyInt())).thenReturn(List.of());

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        sessions = mock(PlayerSessionCoordinator.class);
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

    /** An event of this type, carrying this payload, written this long ago. */
    private static ActivityEvent eventOf(String payloadTypeId, String payload, Instant when) {
        return new ActivityEvent(
                UUID.randomUUID(),
                ISLAND.value().toString(),
                PROFILE,
                ActivityEventType.BANK_DEPOSIT,
                ActivityVisibility.MEMBERS_ONLY,
                payloadTypeId,
                1,
                payload,
                when);
    }

    /** The same command over the real catalogue, for the lines a player actually reads. */
    private CommandDispatcher<CommandSourceStack> overTheRealCatalogue() {
        IslandActivityCommands commands =
                new IslandActivityCommands(() -> feed, locations, inlineScheduler(), Messages.bundled(), sessions);
        CommandDispatcher<CommandSourceStack> tree = new CommandDispatcher<>();
        tree.register(commands.build());
        return tree;
    }

    private void runOn(CommandDispatcher<CommandSourceStack> tree, String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        tree.execute(line, source);
    }

    /** The last thing the player was told, which is the entry when there is exactly one. */
    private String lastLine() {
        String last = null;
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            last = line;
        }
        return java.util.Objects.requireNonNull(last, "the player was told nothing at all");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An entry whose type the catalogue answers is written in that type's own words")
    void anentryOfAKnownTypeUsesItsOwnLine() throws Exception {
        when(feed.getRecentActivities(anyString(), anyInt()))
                .thenReturn(List.of(eventOf(
                        "activity.bank_deposit",
                        com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(
                                java.util.Map.of("player", "Ayse", "amount", "250")),
                        Instant.now().minus(java.time.Duration.ofHours(2)))));

        runOn(overTheRealCatalogue(), "activity");

        assertThat(lastLine())
                .describedAs("the type's own sentence, carrying what the writer put in it")
                .contains("Ayse")
                .contains("250")
                .contains("2h")
                // The words belong to activity.bank_deposit and to nothing else. Without them this
                // passes on the fallback line too, because that one prints the whole payload as the
                // body and the payload holds both of those values as plain text.
                .contains("into the bank")
                .doesNotContain("BANK_DEPOSIT");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A category, a role and a preset read in the viewer's language, stored as a key or as a bare id")
    void storedNamesReadInTheViewersLanguage() throws Exception {
        player.setLocale(java.util.Locale.forLanguageTag("tr"));
        Instant now = Instant.now();
        when(feed.getRecentActivities(anyString(), anyInt()))
                .thenReturn(List.of(
                        eventOf(
                                "activity.warp_created",
                                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(java.util.Map.of(
                                        "player", "Ayse", "name", "kapi", "category", "@warp.categories.general")),
                                now),
                        eventOf(
                                "activity.warp_created",
                                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(
                                        java.util.Map.of("player", "Ayse", "name", "eski", "category", "SHOPS")),
                                now),
                        eventOf(
                                "activity.role_changed",
                                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(
                                        java.util.Map.of("player", "Ayse", "role", "MEMBER")),
                                now),
                        eventOf(
                                "activity.template_applied",
                                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(
                                        java.util.Map.of("player", "Ayse", "preset", "classic")),
                                now)));

        runOn(overTheRealCatalogue(), "activity");

        List<String> lines = new java.util.ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            lines.add(line);
        }
        String said = String.join("\n", lines);
        assertThat(said)
                .contains("Genel")
                .contains("Dükkanlar ve Pazarlar")
                .contains("Üye")
                .doesNotContain("GENERAL")
                .doesNotContain("SHOPS")
                .doesNotContain("MEMBER")
                .doesNotContain("classic");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An entry whose type the catalogue does not answer still reaches the player")
    void anentryOfAnUnknownTypeFallsBack() throws Exception {
        when(feed.getRecentActivities(anyString(), anyInt()))
                .thenReturn(List.of(eventOf(
                        "something.nobody.wrote",
                        com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(
                                java.util.Map.of("body", "a thing happened")),
                        Instant.now().minus(java.time.Duration.ofMinutes(5)))));

        runOn(overTheRealCatalogue(), "activity");

        assertThat(lastLine())
                .describedAs("a type added later must not go unread until somebody writes a line for it")
                .contains("a thing happened")
                .contains("5m")
                .describedAs("and it is the fallback line, which names the type it could not write")
                .contains("BANK_DEPOSIT");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("How long ago is said in the coarsest unit that is still true, seconds under a minute")
    void howlongAgoIsTheCoarsestTrueUnit() throws Exception {
        record Case(java.time.Duration since, String reads) {}
        List<Case> cases = List.of(
                new Case(java.time.Duration.ofSeconds(30), "3[0-9]s ago"),
                new Case(java.time.Duration.ofMinutes(5), "5m"),
                new Case(java.time.Duration.ofMinutes(59), "59m"),
                new Case(java.time.Duration.ofHours(3), "3h"),
                new Case(java.time.Duration.ofHours(47), "1d"),
                new Case(java.time.Duration.ofDays(4), "4d"));

        for (Case one : cases) {
            when(feed.getRecentActivities(anyString(), anyInt()))
                    .thenReturn(List.of(eventOf(
                            "something.nobody.wrote",
                            com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(
                                    java.util.Map.of("body", "x")),
                            Instant.now().minus(one.since()))));

            runOn(overTheRealCatalogue(), "activity");

            assertThat(lastLine()).describedAs("%s ago", one.since()).containsPattern(one.reads());
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An entry written in the future is not written in negative time")
    void afutureEntryReadsAsNow() throws Exception {
        when(feed.getRecentActivities(anyString(), anyInt()))
                .thenReturn(List.of(eventOf(
                        "something.nobody.wrote",
                        com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(java.util.Map.of("body", "x")),
                        Instant.now().plus(java.time.Duration.ofHours(1)))));

        runOn(overTheRealCatalogue(), "activity");

        assertThat(lastLine())
                .describedAs("two nodes whose clocks disagree must not make a player read -1h ago")
                .contains("0s ago");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A node with the feed switched off says so rather than saying nothing")
    void thefeedBeingOffIsAnAnswer() throws Exception {
        IslandActivityCommands off =
                new IslandActivityCommands(() -> null, locations, inlineScheduler(), Messages.bundled(), sessions);
        CommandDispatcher<CommandSourceStack> tree = new CommandDispatcher<>();
        tree.register(off.build());

        runOn(tree, "activity");

        assertThat(lastLine()).isNotEmpty();
        verify(feed, org.mockito.Mockito.never()).getRecentActivities(anyString(), anyInt());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A player whose session is not ready is told, and the feed is never asked")
    void asessionThatIsNotReadyIsAnAnswer() throws Exception {
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.empty());

        runOn(overTheRealCatalogue(), "activity");

        assertThat(lastLine()).isNotEmpty();
        verify(feed, org.mockito.Mockito.never()).getRecentActivities(anyString(), anyInt());
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
