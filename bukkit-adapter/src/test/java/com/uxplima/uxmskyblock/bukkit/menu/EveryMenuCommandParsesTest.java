package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
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

/**
 * Every command a menu runs must parse against the real command tree.
 *
 * <p>Ten menus shipped naming commands that did not exist, and the first guard written for it read
 * the source and checked only the first word. {@code /is missions daily} passed it while
 * {@code /is missions} took no argument at all: Brigadier answers a word it has nowhere to put with
 * a usage error, which reads to a player exactly like a broken plugin.
 *
 * <p>So this does not read the source. It builds the tree the plugin registers, hands it to
 * Brigadier, and asks whether each line a menu runs actually parses. A guard that guesses at a
 * structure is believed until the day somebody types the line it guessed about.
 */
class EveryMenuCommandParsesTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    /** A whole command line a menu file runs, for example {@code command:is warp create %input%}. */
    private static final Pattern MENU_COMMAND = Pattern.compile("\"command:(is [a-z_0-9 %]+)\"");

    /**
     * What a typed line looks like when the guard asks Brigadier.
     *
     * <p>A {@code %input%} token is the word the player types at an {@code input:} prompt, substituted
     * by the engine before the command runs. A word stands in for it here, because what is being
     * checked is whether the command has somewhere to put a word, not which word.
     */
    private static final String SAMPLE_INPUT = "example";

    /** The tree the plugin registers, built with stand-ins for everything it talks to. */
    private static CommandDispatcher<CommandSourceStack> dispatcher() {
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
                protectionListenerWithIndex(),
                mock(PlayerSessionCoordinator.class),
                mock(SchedulerPort.class),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree.buildRoot());
        return dispatcher;
    }

    /** A source that is allowed everything, so a permission gate never hides a missing branch. */
    private static CommandSourceStack permittedSource() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.isOp()).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    private static List<String> menuCommandLines() throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                Matcher matcher = MENU_COMMAND.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    lines.add(file.getFileName() + " :: " + matcher.group(1).strip());
                }
            }
        }
        return lines;
    }

    @Test
    @DisplayName("Every verb the root itself declares parses and runs something")
    void theRootVerbsParse() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        assertRuns(dispatcher, "island help");
        assertRuns(dispatcher, "island menu");
        assertRuns(dispatcher, "island settings");
        assertRuns(dispatcher, "island profile switch 00000000-0000-0000-0000-000000000001");
    }

    /** A line that parses whole and ends on a node that actually runs something. */
    private static void assertRuns(CommandDispatcher<CommandSourceStack> dispatcher, String line) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(line, permittedSource());
        assertThat(parsed.getReader().getRemaining())
                .describedAs("what Brigadier could not read of \"%s\"", line)
                .isEmpty();
        assertThat(parsed.getContext().getNodes())
                .describedAs("nodes matched by \"%s\"", line)
                .isNotEmpty();
        assertThat(parsed.getContext().getNodes().getLast().getNode().getCommand())
                .describedAs("\"%s\" parses but runs nothing", line)
                .isNotNull();
    }

    @Test
    @DisplayName("Every command a shipped menu runs parses against the real command tree")
    void everyMenuCommandParses() throws IOException {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = permittedSource();
        List<String> lines = menuCommandLines();
        assertThat(lines).describedAs("the guard really read the menu files").isNotEmpty();

        TreeSet<String> broken = new TreeSet<>();
        for (String entry : lines) {
            String line = entry.substring(entry.indexOf(" :: ") + 4);
            // The tree is registered under "island"; "is" is its alias, which Paper adds and Brigadier
            // here does not, so the line is asked under the name the tree itself declares.
            String asRegistered = ("island" + line.substring("is".length())).replace("%input%", SAMPLE_INPUT);
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(asRegistered, source);
            String remaining = parsed.getReader().getRemaining();
            if (!remaining.isEmpty() || parsed.getContext().getNodes().isEmpty()) {
                broken.add(entry + "  (Brigadier stopped at: '" + remaining + "')");
                continue;
            }
            // Parsing the whole line is not enough. /is bank deposit reads to the end and still runs
            // nothing, because deposit needs an amount: Brigadier calls that an incomplete command and
            // the player sees usage text where they expected their money to move.
            List<com.mojang.brigadier.context.ParsedCommandNode<CommandSourceStack>> nodes =
                    parsed.getContext().getNodes();
            if (nodes.get(nodes.size() - 1).getNode().getCommand() == null) {
                broken.add(entry + "  (parses, but runs nothing: the line is incomplete)");
            }
        }

        assertThat(broken)
                .describedAs("a menu slot that runs a line Brigadier cannot parse reads to a player "
                        + "exactly like a broken plugin")
                .isEmpty();
    }

    /**
     * A protection listener whose spatial index is real.
     *
     * <p>The command tree asks it where an island is, and a bare mock answers null: the tree then
     * refuses to build and the test fails for a reason that has nothing to do with what it checks.
     */
    private static IslandProtectionListener protectionListenerWithIndex() {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        return listener;
    }
}
