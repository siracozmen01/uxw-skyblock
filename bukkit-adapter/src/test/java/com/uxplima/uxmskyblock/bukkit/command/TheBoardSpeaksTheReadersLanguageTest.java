package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is top} says an unnamed island and a level in the reader's language.
 *
 * <p>The board's lines came out of the database adapter already worded, "Island 42ffcb2a" and
 * "Level 0", and a Turkish player on a live server read both in English inside a Turkish line. The
 * adapter still words them for the API, and the command words them for each player.
 */
class TheBoardSpeaksTheReadersLanguageTest extends MockBukkitHarness {

    private static final IslandId UNNAMED = IslandId.fromString("42ffcb2a-0000-0000-0000-000000000001");
    private static final IslandId NAMED = IslandId.of(UUID.randomUUID());

    private IslandLeaderboardService leaderboard;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        leaderboard = mock(IslandLeaderboardService.class);
        when(leaderboard.getTop(any(LeaderboardCategory.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        new LeaderboardEntry(1, UNNAMED, "Island 42ffcb2a", 7L, "Level 7", false),
                        new LeaderboardEntry(2, NAMED, "Güneş Adası", 3L, "Level 3", true)));
        when(leaderboard.getRank(any(), any())).thenReturn(OptionalInt.empty());
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
                Messages.bundled());
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildTop());
    }

    @Test
    @DisplayName("A Turkish reader sees Ada and Seviye, and a given name as it was given")
    void turkishWordsOnTheBoard() throws Exception {
        PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));

        List<String> said = top(turkish);

        assertThat(said).anyMatch(line -> line.contains("#1 Ada 42ffcb2a") && line.contains("Seviye 7"));
        assertThat(said).anyMatch(line -> line.contains("#2 Güneş Adası") && line.contains("Seviye 3"));
        assertThat(String.join("\n", said)).doesNotContain("Island").doesNotContain("Level");
    }

    @Test
    @DisplayName("An English reader sees Island and Level")
    void englishWordsOnTheBoard() throws Exception {
        List<String> said = top(createPlayer("Reader"));

        assertThat(said).anyMatch(line -> line.contains("#1 Island 42ffcb2a") && line.contains("Level 7"));
    }

    private List<String> top(PlayerMock player) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn((CommandSender) player);
        dispatcher.execute("top", source);
        List<String> said = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            said.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return said;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A money board names the currency the way the catalogue does, which the operator may change")
    void moneyIsWrittenByTheCatalogue() throws Exception {
        when(leaderboard.getTop(any(LeaderboardCategory.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new LeaderboardEntry(1, NAMED, "Güneş Adası", 123_450L, "$1,234.50", true)));
        com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider provider =
                new com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
        provider.loadFromStream(
                "en",
                new java.io.ByteArrayInputStream("leaderboard { score_money = \"<amount> TL\" }"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
                Messages.of(provider, com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration.defaults()));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildTop());
        PlayerMock reader = createPlayer("Reader");

        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn((CommandSender) reader);
        dispatcher.execute("top bank", source);
        List<String> said = new ArrayList<>();
        for (Component line = reader.nextComponentMessage(); line != null; line = reader.nextComponentMessage()) {
            said.add(PlainTextComponentSerializer.plainText().serialize(line));
        }

        assertThat(said).anyMatch(line -> line.contains("1,234.50 TL")).noneMatch(line -> line.contains("$"));
    }
}
