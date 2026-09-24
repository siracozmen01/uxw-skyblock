package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is help} lists every command a player can use, under the words the server gave it.
 *
 * <p>It was twenty nine fixed lines, and the command had seventy eight branches: invite, members,
 * warp and shop were never named, and a renamed word still printed as the shipped one. Help is now
 * drawn from the registered command, a page at a time, in the reader's language.
 */
class IslandHelpListsEveryCommandTest extends MockBukkitHarness {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Every branch that is not a second word for another has usage and words in English and Turkish")
    void everyBranchHasHelp() {
        IslandCommandTree tree = tree(null);
        MessageProvider provider = Messages.bundled().provider();
        List<String> missing = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : tree.buildRoot().getArguments()) {
            if (!(child instanceof LiteralCommandNode<CommandSourceStack> literal)
                    || List.of(
                                    "ac",
                                    "c",
                                    "ally",
                                    "chest",
                                    "explore",
                                    "upgrade",
                                    "go",
                                    "bounds",
                                    "value",
                                    "challenges",
                                    "recalc",
                                    "settings")
                            .contains(literal.getLiteral())) {
                continue;
            }
            for (String locale : List.of("en", "tr")) {
                for (String part : List.of("usage", "description")) {
                    String key = "help.commands." + literal.getLiteral() + "." + part;
                    if (!provider.getKeys(locale).contains(key)) {
                        missing.add(locale + ": " + key);
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName(
            "The pages together name invite, members, warp and shop, keep angle brackets and show each command once")
    void thePagesNameEveryCommandOnce() throws Exception {
        PlayerMock player = allowedEverything("Reader");
        List<String> said = allPages(tree(null), player, "island");

        assertThat(said)
                .anyMatch(line -> line.startsWith("/is invite <player>"))
                .anyMatch(line -> line.startsWith("/is members"))
                .anyMatch(line -> line.startsWith("/is warp"))
                .anyMatch(line -> line.startsWith("/is shop"))
                .anyMatch(line -> line.startsWith("/is menu"))
                .noneMatch(line -> line.startsWith("/is chest"));
        assertThat(said.stream().filter(line -> line.startsWith("/is vault")).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A Turkish reader reads Turkish, and a renamed command is listed under its new word")
    void helpFollowsTheReaderAndTheFile() throws Exception {
        Path file = dir.resolve("commands.conf");
        Files.writeString(file, """
                commands { island { name = "ada", subcommands { members { name = "uyeler" } } } }
                """);
        PlayerMock turkish = allowedEverything("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));

        List<String> said = allPages(tree(ConfiguredCommands.load(file)), turkish, "ada");

        assertThat(said).anyMatch(line -> line.startsWith("/ada uyeler") && line.contains("Ada üyelerini listeler"));
        assertThat(said).noneMatch(line -> line.startsWith("/ada members"));
    }

    @Test
    @DisplayName("A command the player may not use is not listed")
    void aClosedCommandIsNotListed() throws Exception {
        PlayerMock player = createPlayer("Reader");
        player.addAttachment(
                        server.getPluginManager().getPlugins().length > 0
                                ? server.getPluginManager().getPlugins()[0]
                                : org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin())
                .setPermission("uxmskyblock.island.invite", false);

        List<String> said = allPages(tree(null), player, "island");

        assertThat(said).noneMatch(line -> line.startsWith("/is invite"));
        assertThat(said).anyMatch(line -> line.startsWith("/is members"));
    }

    private PlayerMock allowedEverything(String name) {
        PlayerMock player = createPlayer(name);
        player.setOp(true);
        return player;
    }

    /** Every entry line of every page, read until the pages run out. */
    private static List<String> allPages(IslandCommandTree tree, PlayerMock player, String root) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree.buildRoot());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn((CommandSender) player);
        List<String> entries = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            dispatcher.execute(root + " help " + page, source);
            boolean more = false;
            for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
                String text = PlainTextComponentSerializer.plainText().serialize(line);
                String unprefixed =
                        text.contains("» ") && text.indexOf("» ") < 12 ? text.substring(text.indexOf("» ") + 2) : text;
                if (unprefixed.startsWith("/")) {
                    entries.add(unprefixed);
                } else if (unprefixed.contains(" help " + (page + 1))) {
                    more = true;
                }
            }
            if (!more) {
                break;
            }
        }
        return entries;
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
                new InlineSchedulerPort(),
                Messages.bundled(),
                HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");
        tree.useCommandNames(names);
        return tree;
    }
}
