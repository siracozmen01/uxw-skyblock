package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
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
 * The vault log has a way in, under every word the vault answers to.
 *
 * <p>The branch is built by a helper that takes the verb as an argument, so {@code /is vault} and
 * {@code /is chest} are the same tree twice. A reader added to one and not the other is the kind of
 * thing that only shows up when a player types the other word.
 */
class TheVaultLogBranchParsesTest {

    private static IslandProtectionListener protectionListenerWithIndex() {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        return listener;
    }

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

    private static CommandSourceStack permittedSource() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.isOp()).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

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
    @DisplayName("The vault log parses with and without a line count, under both words")
    void theLogParsesUnderBothWords() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        assertRuns(dispatcher, "island vault log");
        assertRuns(dispatcher, "island vault log 25");
        assertRuns(dispatcher, "island chest log");
        assertRuns(dispatcher, "island chest log 25");
    }

    @Test
    @DisplayName("Opening a page still parses, so the reader did not take the page argument's place")
    void openingAPageStillParses() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        assertRuns(dispatcher, "island vault");
        assertRuns(dispatcher, "island vault 3");
        assertRuns(dispatcher, "island chest 3");
    }
}
