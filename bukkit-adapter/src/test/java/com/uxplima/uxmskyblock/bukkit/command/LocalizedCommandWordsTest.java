package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The island command and its branches carry words for the readers of one language, as every plugin's do.
 *
 * <p>The family reads {@code localized-aliases} through the library's annotated commands. This command is a
 * tree of its own, so the library's reading never reached it: a word written there was read and dropped.
 */
class LocalizedCommandWordsTest {

    private static final Path SHIPPED = Path.of("src/main/resources/commands.conf");

    @TempDir
    Path dir;

    @Test
    @DisplayName("The shipped commands file explains localized-aliases and ships no word")
    void theShippedFileExplainsTheField() throws Exception {
        assertThat(Files.readString(SHIPPED, StandardCharsets.UTF_8)).contains("localized-aliases");
        ConfiguredCommandTree<CommandSourceStack> shipped =
                new ConfiguredCommandTree<>(ConfiguredCommands.load(SHIPPED));
        assertThat(shipped.rootLocalized()).isEmpty();
        assertThat(shipped.rootAliases()).containsExactly("is");
    }

    @Test
    @DisplayName("A root word for Turkish readers is one more alias, filed under the language it is for")
    void aRootWordIsAnAlias() throws Exception {
        ConfiguredCommandTree<CommandSourceStack> names = new ConfiguredCommandTree<>(ConfiguredCommands.load(file()));

        assertThat(names.rootAliases()).containsExactly("is", "ada");
        assertThat(names.rootLocalized()).isEqualTo(Map.of("ada", List.of("tr")));
    }

    @Test
    @DisplayName("A branch word for Turkish readers answers them and the console, and nobody else")
    void aBranchWordAnswersItsReaders() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree(ConfiguredCommands.load(file())).buildRoot());

        assertThat(parses(dispatcher, "island davet Ayse", player(Locale.forLanguageTag("tr-TR"))))
                .isTrue();
        assertThat(parses(dispatcher, "island davet Ayse", player(Locale.ENGLISH)))
                .describedAs("a player who reads English")
                .isFalse();
        assertThat(parses(dispatcher, "island davet Ayse", mock(CommandSender.class)))
                .describedAs("the console")
                .isTrue();
        assertThat(parses(dispatcher, "island invite Ayse", player(Locale.ENGLISH)))
                .describedAs("the branch's own word")
                .isTrue();
    }

    @Test
    @DisplayName("A tag with a country names that country's readers only")
    void aCountryNarrowsTheReaders() {
        assertThat(ConfiguredCommandTree.readerOf("pt-BR", Locale.forLanguageTag("pt-BR")))
                .isTrue();
        assertThat(ConfiguredCommandTree.readerOf("pt-BR", Locale.forLanguageTag("pt-PT")))
                .isFalse();
        assertThat(ConfiguredCommandTree.readerOf("pt", Locale.forLanguageTag("pt-PT")))
                .isTrue();
    }

    private Path file() throws Exception {
        Path file = dir.resolve("commands.conf");
        Files.writeString(file, """
                commands {
                  island {
                    name = "island"
                    aliases = ["is"]
                    localized-aliases { tr = ["ada"] }
                    subcommands {
                      invite { name = "invite", localized-aliases { tr = ["davet"] } }
                    }
                  }
                }
                """);
        return file;
    }

    private static Player player(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }

    private static boolean parses(CommandDispatcher<CommandSourceStack> dispatcher, String line, CommandSender sender) {
        when(sender.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(sender.isOp()).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        var parsed = dispatcher.parse(line, source);
        return parsed.getExceptions().isEmpty()
                && !parsed.getReader().canRead()
                && parsed.getContext().getCommand() != null;
    }

    private static IslandCommandTree tree(ConfiguredCommands names) {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        IslandCommandTree tree = new IslandCommandTree(
                mock(CreateIslandUseCase.class),
                mock(IslandLocationService.class),
                mock(IslandFlagService.class),
                mock(IslandBankService.class),
                mock(IslandUpgradeStoragePort.class),
                mock(IslandLeaderboardService.class),
                mock(BiomeModificationPort.class),
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                listener,
                mock(PlayerSessionCoordinator.class),
                mock(SchedulerPort.class),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");
        tree.useCommandNames(names);
        return tree;
    }
}
