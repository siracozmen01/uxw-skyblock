package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The social verbs act on the island under the player's feet.
 *
 * <p>They used to act on the caller's own island, which made the guestbook a book nobody signs and
 * the rating a score you can only give yourself. The island is resolved through the spatial index
 * now, and what these tests are really about is that the subject is the one where the player is
 * standing and not the one they own.
 */
class IslandSocialCommandsTest {

    private static final IslandId SOMEONE_ELSES = IslandId.of(UUID.randomUUID());
    private static final ProfileId VISITOR = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandSocialService social;
    private SpatialIslandIndex index;
    private com.uxplima.uxmskyblock.core.application.island.IslandLocationService locations;
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

    private static Island islandAt(IslandId id) {
        return Island.create(
                id,
                new IslandBounds(-50, -50, 50, 50, 0, 0, 50),
                PlayerUuid.of(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer();

        social = mock(IslandSocialService.class);
        index = mock(SpatialIslandIndex.class);
        // Wherever the player is standing, it is not their own island.
        when(index.findIslandAt(any(String.class), anyInt(), anyInt()))
                .thenReturn(Optional.of(islandAt(SOMEONE_ELSES)));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(VISITOR));

        locations = mock(com.uxplima.uxmskyblock.core.application.island.IslandLocationService.class);
        IslandSocialCommands commands = new IslandSocialCommands(
                () -> social, index, locations, inlineScheduler(), Messages.bundled(), sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildGuestbook());
        dispatcher.register(commands.buildRate());
        dispatcher.register(commands.buildBookmarks());
    }

    /** Makes the island the player is standing on theirs, so they may moderate its page. */
    private void theirOwnIsland() {
        Island theirs = Island.create(
                SOMEONE_ELSES,
                new IslandBounds(-50, -50, 50, 50, 0, 0, 50),
                PlayerUuid.of(player.getUniqueId()),
                VISITOR,
                Instant.now());
        when(locations.findIsland(SOMEONE_ELSES)).thenReturn(Optional.of(theirs));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An owner pins an entry on their own page")
    void anOwnerPins() throws Exception {
        theirOwnIsland();

        run("guestbook pin rev-1", player);

        verify(social).pinGuestbookEntry(eq(SocialSubjectRef.island(SOMEONE_ELSES)), eq("rev-1"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A visitor cannot moderate the page they are standing on")
    void avisitorCannotModerate() throws Exception {
        run("guestbook hide rev-1", player);

        verify(social, org.mockito.Mockito.never())
                .hideGuestbookEntry(
                        any(SocialSubjectRef.class),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(player.nextMessage()).describedAs("the refusal").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Hiding, showing, unpinning and deleting all reach the service with the island attached")
    void everyVerbCarriesTheIsland() throws Exception {
        theirOwnIsland();
        when(social.hideGuestbookEntry(
                        any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        when(social.unpinGuestbookEntry(any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        when(social.deleteGuestbookEntry(any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        SocialSubjectRef subject = SocialSubjectRef.island(SOMEONE_ELSES);

        run("guestbook hide rev-1", player);
        run("guestbook show rev-2", player);
        run("guestbook unpin rev-3", player);
        run("guestbook delete rev-4", player);

        verify(social).hideGuestbookEntry(eq(subject), eq("rev-1"), eq(true));
        verify(social).hideGuestbookEntry(eq(subject), eq("rev-2"), eq(false));
        verify(social).unpinGuestbookEntry(eq(subject), eq("rev-3"));
        verify(social).deleteGuestbookEntry(eq(subject), eq("rev-4"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An entry that is not on this page is reported, not silently ignored")
    void anUnknownEntryIsReported() throws Exception {
        theirOwnIsland();
        when(social.deleteGuestbookEntry(any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);

        run("guestbook delete nothing", player);

        assertThat(player.nextMessage()).describedAs("told it is not there").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A page already at its pin limit says so, naming the operator's number")
    void afullPageSaysSo() throws Exception {
        theirOwnIsland();
        when(social.maxPinnedEntries()).thenReturn(3);
        org.mockito.Mockito.doThrow(
                        new com.uxplima.uxmskyblock.core.domain.social.GuestbookPinnedLimitExceededException("full"))
                .when(social)
                .pinGuestbookEntry(any(), org.mockito.ArgumentMatchers.anyString());

        run("guestbook pin rev-1", player);

        assertThat(player.nextMessage())
                .describedAs("the refusal names the number")
                .contains("3");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An owner reading their own page sees what is hidden on it")
    void anOwnerSeesHiddenEntries() throws Exception {
        theirOwnIsland();

        run("guestbook", player);

        verify(social)
                .listGuestbookEntries(
                        eq(SocialSubjectRef.island(SOMEONE_ELSES)),
                        eq(true),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
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
    @DisplayName("Reading the guestbook reads the one where the player stands")
    void readingTheGuestbookReadsWhereTheyStand() throws Exception {
        when(social.listGuestbookEntries(any(), eq(false), anyInt(), anyInt())).thenReturn(List.of());

        run("guestbook", player);

        verify(social).listGuestbookEntries(eq(SocialSubjectRef.island(SOMEONE_ELSES)), eq(false), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Signing writes on the island under the player, as the player")
    void signingWritesWhereTheyStand() throws Exception {
        run("guestbook sign nice build", player);

        verify(social)
                .signGuestbook(
                        eq(SocialSubjectRef.island(SOMEONE_ELSES)), eq(VISITOR), eq("nice build"), any(Instant.class));
    }

    @Test
    @DisplayName("Rating scores the island under the player")
    void ratingScoresWhereTheyStand() throws Exception {
        run("rate 4", player);

        verify(social).rate(eq(SocialSubjectRef.island(SOMEONE_ELSES)), eq(VISITOR), eq(4), any(Instant.class));
    }

    @Test
    @DisplayName("A refused rating is told from the catalogue, by what kind of refusal it is")
    void aRefusedRatingIsToldByKind() throws Exception {
        org.mockito.Mockito.doThrow(new com.uxplima.uxmskyblock.core.domain.social.SelfRatingNotAllowedException(
                        "Players cannot rate their own island"))
                .when(social)
                .rate(any(), any(), anyInt(), any());
        run("rate 4", player);
        assertThat(lastMessage()).contains("You cannot rate your own island");

        org.mockito.Mockito.doThrow(new com.uxplima.uxmskyblock.core.domain.social.DwellTimeNotMetException(
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(12)))
                .when(social)
                .rate(any(), any(), anyInt(), any());
        run("rate 4", player);
        assertThat(lastMessage()).contains("You can rate this island in").contains("12s");

        org.mockito.Mockito.doThrow(new IllegalStateException("Failed to persist rating for subject"))
                .when(social)
                .rate(any(), any(), anyInt(), any());
        run("rate 4", player);
        assertThat(lastMessage()).contains("Your rating could not be saved").doesNotContain("persist");
    }

    @Test
    @DisplayName("A guestbook message that is too long says how long one may be")
    void aLongGuestbookMessageSaysTheLimit() throws Exception {
        org.mockito.Mockito.doThrow(
                        new com.uxplima.uxmskyblock.core.domain.social.GuestbookMessageTooLongException(300, 200))
                .when(social)
                .signGuestbook(any(), any(), any(), any());

        run("guestbook sign far too long", player);

        assertThat(lastMessage()).contains("at most").contains("200");
    }

    @Test
    @DisplayName("No social line has room for a raw refusal reason")
    void noSocialLineCarriesAReason() throws Exception {
        for (String language : new String[] {"en", "tr"}) {
            try (var in = getClass().getResourceAsStream("/messages/messages_" + language + ".conf")) {
                String catalogue = new String(
                        java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String social = catalogue.substring(catalogue.indexOf("\nsocial {"));
                social = social.substring(0, social.indexOf("\n}"));
                assertThat(social).describedAs(language).doesNotContain("<reason>");
            }
        }
    }

    private String lastMessage() {
        String last = null;
        for (String next = player.nextMessage(); next != null; next = player.nextMessage()) {
            last = next;
        }
        return java.util.Objects.requireNonNull(last, "nothing was said");
    }

    @Test
    @DisplayName("A score outside one to five never reaches the service")
    void aScoreOutOfRangeIsRefusedHere() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("rate 9", player))
                .describedAs("the argument type refuses it before the handler runs");
        org.mockito.Mockito.verifyNoInteractions(social);
    }

    @Test
    @DisplayName("The bare rate command shows the summary and scores nothing")
    void theBareRateCommandShows() throws Exception {
        when(social.getRatingSummary(any()))
                .thenReturn(new RatingSummary(SocialSubjectRef.island(SOMEONE_ELSES), 3, 4.0, 3.6));

        run("rate", player);

        verify(social).getRatingSummary(eq(SocialSubjectRef.island(SOMEONE_ELSES)));
        verify(social, never()).rate(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("Bookmarks list the caller's own saved islands, not the one underfoot")
    void bookmarksAreTheCallersOwn() throws Exception {
        when(social.listBookmarks(VISITOR)).thenReturn(List.of());

        run("bookmarks", player);

        verify(social).listBookmarks(VISITOR);
    }

    @Test
    @DisplayName("Toggling a bookmark saves the island underfoot")
    void togglingSavesWhereTheyStand() throws Exception {
        run("bookmarks toggle", player);

        verify(social).toggleBookmark(eq(VISITOR), eq(SocialSubjectRef.island(SOMEONE_ELSES)));
    }

    @Test
    @DisplayName("Standing on no island touches nothing")
    void standingNowhereTouchesNothing() throws Exception {
        when(index.findIslandAt(any(String.class), anyInt(), anyInt())).thenReturn(Optional.empty());

        run("guestbook sign hello", player);

        verify(social, never()).signGuestbook(any(), any(), any(), any());
    }
}
