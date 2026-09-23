package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
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
 * Every word of the island command is the operator's to change.
 *
 * <p>The command and each of its sixty odd branches were written in Java, so a server that already had
 * an {@code /island} from another plugin, or one that plays in Turkish, could not rename one word or
 * turn one off. Each is now read from {@code commands.conf}, and the shipped file names every branch so
 * an operator finds the key without reading the code.
 */
class EveryCommandWordIsTheOperatorsTest {

    private static final Path SHIPPED = Path.of("src/main/resources/commands.conf");

    @TempDir
    Path dir;

    @Test
    @DisplayName("The shipped commands file names the root and every branch the command has")
    void theShippedFileNamesEveryBranch() {
        ConfiguredCommands shipped = ConfiguredCommands.load(SHIPPED);
        List<String> unnamed = new ArrayList<>();
        for (String branch : branches(tree(null).buildRoot())) {
            String key = ConfiguredCommands.branchKey(ConfiguredCommandTree.ROOT, branch);
            ConfiguredCommands.Entry entry = shipped.entryOf(key, "-");
            if (!branch.equals(entry.name())) {
                unnamed.add(branch);
            }
        }
        assertThat(unnamed)
                .describedAs("branches the shipped file does not name")
                .isEmpty();
        assertThat(shipped.entryOf(ConfiguredCommandTree.ROOT, "-").name()).isEqualTo("island");
        assertThat(shipped.entryOf(ConfiguredCommandTree.ROOT, "-").aliases()).containsExactly("is");
    }

    @Test
    @DisplayName(
            "A renamed root and branch answer to their new words, an alias answers too and a branch turned off is gone")
    void theFileRenamesAliasesAndTurnsOff() throws Exception {
        Path file = dir.resolve("commands.conf");
        Files.writeString(file, """
                commands {
                  island {
                    name = "ada"
                    aliases = ["a"]
                    subcommands {
                      invite { name = "davet", aliases = ["d"] }
                      warp { enabled = false }
                    }
                  }
                }
                """);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree(ConfiguredCommands.load(file)).buildRoot());

        assertThat(parses(dispatcher, "ada davet Ayse")).isTrue();
        assertThat(parses(dispatcher, "ada d Ayse")).isTrue();
        assertThat(parses(dispatcher, "ada invite Ayse"))
                .describedAs("the old word")
                .isFalse();
        assertThat(parses(dispatcher, "ada warp create kapi"))
                .describedAs("a branch turned off")
                .isFalse();
        assertThat(parses(dispatcher, "ada home"))
                .describedAs("a branch the file is silent on")
                .isTrue();
        assertThat(parses(dispatcher, "island home"))
                .describedAs("the old root")
                .isFalse();
    }

    @Test
    @DisplayName("A name another branch already answers to is refused, and the branch keeps its own word")
    void aNameAnotherBranchHasIsRefused() throws Exception {
        Path file = dir.resolve("commands.conf");
        Files.writeString(file, """
                commands { island { subcommands { home { name = "visit" } } } }
                """);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree(ConfiguredCommands.load(file)).buildRoot());

        assertThat(parses(dispatcher, "island home")).isTrue();
        assertThat(parses(dispatcher, "island visit Ayse")).isTrue();
    }

    private static boolean parses(CommandDispatcher<CommandSourceStack> dispatcher, String line) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(sender.isOp()).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        var parsed = dispatcher.parse(line, source);
        return parsed.getExceptions().isEmpty()
                && !parsed.getReader().canRead()
                && parsed.getContext().getCommand() != null;
    }

    private static java.util.SortedSet<String> branches(LiteralArgumentBuilder<CommandSourceStack> root) {
        TreeSet<String> names = new TreeSet<>();
        for (CommandNode<CommandSourceStack> child : root.getArguments()) {
            if (child instanceof LiteralCommandNode<CommandSourceStack> literal) {
                names.add(literal.getLiteral());
            }
        }
        return names;
    }

    private static IslandCommandTree tree(@org.jspecify.annotations.Nullable ConfiguredCommands names) {
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
