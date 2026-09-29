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
import com.uxplima.uxmskyblock.bukkit.poseidon.PoseidonLore;
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
 * The lore branch opens the lore of the sea, and says so when the server has no Poseidon islands.
 */
class TheLoreBranchOpensTheLoreTest {

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
    @DisplayName("The branch hands the player to the lore")
    void theBranchOpensTheLore() throws Exception {
        PoseidonLore lore = mock(PoseidonLore.class);
        tree.usePoseidonLore(lore);

        dispatcher().execute("island lore", source());

        verify(lore).open(player);
    }

    @Test
    @DisplayName("With Poseidon off the branch says the island is no Poseidon island")
    void withPoseidonOffThePlayerIsTold() throws Exception {
        dispatcher().execute("island lore", source());

        verify(player).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("Nothing opens once the lore is taken away")
    void noLoreOpensNothing() throws Exception {
        PoseidonLore lore = mock(PoseidonLore.class);
        tree.usePoseidonLore(lore);
        tree.usePoseidonLore(null);

        dispatcher().execute("island lore", source());

        verify(lore, never()).open(any());
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
