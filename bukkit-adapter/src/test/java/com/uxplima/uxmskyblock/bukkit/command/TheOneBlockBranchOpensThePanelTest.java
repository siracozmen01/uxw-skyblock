package com.uxplima.uxmskyblock.bukkit.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel;
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
 * The oneblock branch opens the OneBlock panel, and says so when the server has no OneBlock islands.
 */
class TheOneBlockBranchOpensThePanelTest {

    private final IslandCommandTree tree = new IslandCommandTree(
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
            inline(),
            Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
            HomeConfiguration.defaults(),
            ServerNodeId.of("node-1"),
            "world");

    private final Player player = mock(Player.class);

    @Test
    @DisplayName("The branch hands the player to the panel")
    void theBranchOpensThePanel() throws Exception {
        OneBlockPanel panel = mock(OneBlockPanel.class);
        tree.useOneBlock(panel);

        dispatcher().execute("island oneblock", source());

        verify(panel).open(player);
    }

    @Test
    @DisplayName("With OneBlock off the branch says the island is no OneBlock island")
    void withOneBlockOffThePlayerIsTold() throws Exception {
        dispatcher().execute("island oneblock", source());

        verify(player).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("Nothing opens for a player the panel was never given")
    void noPanelOpensNothing() throws Exception {
        OneBlockPanel panel = mock(OneBlockPanel.class);
        tree.useOneBlock(panel);
        tree.useOneBlock(null);

        dispatcher().execute("island oneblock", source());

        verify(panel, never()).open(any());
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree.buildRoot());
        return dispatcher;
    }

    private CommandSourceStack source() {
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        return source;
    }

    /** A scheduler that runs what the player's thread would run, at once. */
    private static SchedulerPort inline() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        org.mockito.Mockito.doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    private static IslandProtectionListener protectionListenerWithIndex() {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        return listener;
    }
}
