package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.tree.CommandNode;
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
 * Writes down every verb the command tree really declares.
 *
 * <p>{@code scripts/check-docs.sh} compares the documents against this list. It used to grep the
 * sources for {@code literal("x")}, which stopped being true the moment a branch was built by a
 * helper that takes the word as an argument: {@code /is vault} still existed and the grep said it
 * did not. A guard that reads source text is believed until the day the text changes shape.
 *
 * <p>The list is written under {@code build/}, outside version control, because it is a fact about
 * the code rather than a copy of it. The script says so when it is missing.
 */
class CommandVerbsAreWrittenDownTest {

    private static final Path VERBS_FILE = Path.of("build/command-verbs.txt");

    private static IslandProtectionListener protectionListenerWithIndex() {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        return listener;
    }

    private static java.util.SortedSet<String> rootVerbs() {
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

        java.util.SortedSet<String> verbs = new TreeSet<>();
        for (CommandNode<CommandSourceStack> child : tree.buildRoot().getArguments()) {
            verbs.add(child.getName());
        }
        return verbs;
    }

    @Test
    @DisplayName("Every verb the tree declares is written down for the document check")
    void everyVerbIsWrittenDown() throws IOException {
        java.util.SortedSet<String> verbs = rootVerbs();

        assertThat(verbs)
                .describedAs("the tree really was built, rather than an empty root written down")
                .hasSizeGreaterThan(30);
        assertThat(verbs)
                .describedAs("a few the documents name, so an empty list cannot pass for a full one")
                .contains("home", "vault", "chest", "warp", "warps", "explore", "ally", "disband", "warplock");

        Files.createDirectories(VERBS_FILE.getParent());
        Files.writeString(VERBS_FILE, String.join("\n", verbs) + "\n", StandardCharsets.UTF_8);
    }
}
