package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandMetricProviders;
import com.uxplima.uxmskyblock.core.application.leaderboard.LeaderboardMetricRegistry;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is top} shows a board another plugin registered, by its metric id.
 *
 * <p>The leaderboard ranked three categories written into the plugin, and a metric provider had
 * nowhere to be ranked. A registered metric now has a board of its own, read from its owner, named
 * by the reader's language file when that names it and by the owner otherwise.
 */
class ARegisteredMetricHasABoardTest extends MockBukkitHarness {

    private final IslandLeaderboardService leaderboard = mock(IslandLeaderboardService.class);
    private final LeaderboardMetricRegistry registry = new LeaderboardMetricRegistry();

    @SuppressWarnings("NullAway.Init")
    private PlayerMock reader;

    @BeforeEach
    void setUp() {
        when(leaderboard.getTop(any(LeaderboardCategory.class), anyInt())).thenReturn(List.of());
        IslandMetricProviders.registerInto(registry, mock(IslandLeaderboardPort.class));
        registry.register(new SeasonScore());
        reader = createPlayer("Reader");
    }

    @Test
    @DisplayName("A registered metric's board is read from its owner and ranked highest first")
    void aRegisteredMetricIsRanked() throws Exception {
        List<String> said = top("top season:score", Messages.bundled());

        assertThat(said.get(0)).contains("Season score");
        assertThat(said).anyMatch(line -> line.contains("#1 Alpha") && line.contains("30"));
        assertThat(said).anyMatch(line -> line.contains("#2 Beta") && line.contains("10"));
        verify(leaderboard, never()).getTop(any(LeaderboardCategory.class), anyInt());
    }

    @Test
    @DisplayName("The operator's language file names the board over the owner's name")
    void theLanguageFileNamesTheBoard() throws Exception {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
        provider.loadFromStream(
                "en",
                new java.io.ByteArrayInputStream("leaderboard { metrics { season_score = \"Spring league\" } }"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        List<String> said = top(
                "top season:score",
                Messages.of(provider, com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration.defaults()));

        assertThat(said.get(0)).contains("Spring league").doesNotContain("Season score");
    }

    @Test
    @DisplayName("A shipped metric named by its id goes to the board it always had")
    void aShippedIdKeepsItsBoard() throws Exception {
        top("top uxm:worth", Messages.bundled());

        verify(leaderboard).getTop(LeaderboardCategory.WORTH, 10);
    }

    private List<String> top(String line, Messages messages) throws Exception {
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(any(UUID.class))).thenReturn(Optional.empty());
        IslandProgressionCommands commands = new IslandProgressionCommands(
                mock(IslandLocationService.class),
                mock(IslandBankService.class),
                leaderboard,
                mock(BiomeModificationPort.class),
                sessions,
                new InlineSchedulerPort(),
                () -> mock(IslandWorthService.class),
                () -> mock(IslandMissionService.class),
                messages);
        commands.useLeaderboards(() -> registry);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildTop());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn((CommandSender) reader);
        dispatcher.execute(line, source);
        List<String> said = new ArrayList<>();
        for (Component next = reader.nextComponentMessage(); next != null; next = reader.nextComponentMessage()) {
            said.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return said;
    }

    /** A season plugin's score, kept in that plugin. */
    private static final class SeasonScore implements LeaderboardMetricProvider {

        @Override
        public NamespacedId metricId() {
            return NamespacedId.of("season:score");
        }

        @Override
        public String displayName() {
            return "Season score";
        }

        @Override
        public String owner() {
            return "a season plugin";
        }

        @Override
        public String rootType() {
            return IslandMetricProviders.ISLAND;
        }

        @Override
        public SortDirection sortDirection() {
            return SortDirection.HIGHEST_FIRST;
        }

        @Override
        public MetricConsistency consistency() {
            return MetricConsistency.EVENT_DRIVEN_EXACT;
        }

        @Override
        public List<MetricReading> read(int limit) {
            return List.of(new MetricReading("b", "Beta", 10), new MetricReading("a", "Alpha", 30));
        }
    }
}
