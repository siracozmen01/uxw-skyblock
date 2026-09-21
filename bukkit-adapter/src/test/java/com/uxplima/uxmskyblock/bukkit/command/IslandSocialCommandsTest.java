package com.uxplima.uxmskyblock.bukkit.command;

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

        IslandSocialCommands commands = new IslandSocialCommands(
                () -> social,
                index,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildGuestbook());
        dispatcher.register(commands.buildRate());
        dispatcher.register(commands.buildBookmarks());
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
