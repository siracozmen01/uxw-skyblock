package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.TopList;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

/**
 * A leaderboard opens as a window of the islands on it, each under its owner's head, rather than ten lines in chat.
 */
class ABoardIsAWindowTest extends MockBukkitHarness {

    private static final IslandId FIRST = IslandId.of(UUID.randomUUID());

    private final TopList window = mock(TopList.class);
    private PlayerMock reader;
    private PlayerMock owner;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        reader = createPlayer("Reader");
        owner = createPlayer("Ada");
        IslandLeaderboardService leaderboard = mock(IslandLeaderboardService.class);
        when(leaderboard.getTop(any(LeaderboardCategory.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new LeaderboardEntry(1, FIRST, "Sunny", 7L, "Level 7", true)));
        when(leaderboard.getRank(any(), any())).thenReturn(OptionalInt.empty());
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIsland(FIRST))
                .thenReturn(Optional.of(Island.create(
                        FIRST,
                        IslandBounds.fromCenterAndRadius(0, 0, 50),
                        new PlayerUuid(owner.getUniqueId()),
                        new ProfileId(owner.getUniqueId()),
                        Instant.now())));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(any(UUID.class))).thenReturn(Optional.empty());
        IslandProgressionCommands commands = new IslandProgressionCommands(
                locations,
                mock(IslandBankService.class),
                leaderboard,
                mock(BiomeModificationPort.class),
                sessions,
                new InlineSchedulerPort(),
                () -> mock(IslandWorthService.class),
                () -> mock(IslandMissionService.class),
                Messages.bundled());
        commands.useTopList(() -> window);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildTop());
    }

    @Test
    @DisplayName("A board opens as a window of its islands, each with its place, its score and its owner")
    void aBoardIsAWindow() throws Exception {
        when(window.show(eq(reader), anyString(), anyString(), anyList())).thenReturn(true);

        top("top level");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TopList.Entry>> shown = ArgumentCaptor.forClass(List.class);
        verify(window).show(eq(reader), eq("level"), eq("You have no island"), shown.capture());
        assertThat(shown.getValue())
                .containsExactly(new TopList.Entry(1, "Sunny", "Level 7", owner.getUniqueId(), "Ada"));
        assertThat(reader.nextComponentMessage())
                .describedAs("nothing is written in chat")
                .isNull();
    }

    @Test
    @DisplayName("A server whose operator removed the window reads the board in chat")
    void noWindowIsChat() throws Exception {
        when(window.show(eq(reader), anyString(), anyString(), anyList())).thenReturn(false);

        top("top level");

        assertThat(reader.nextComponentMessage()).isNotNull();
    }

    private void top(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn((CommandSender) reader);
        dispatcher.execute(line, source);
    }
}
