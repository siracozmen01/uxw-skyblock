package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
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
 * Taking a permission away closes the verb it names.
 *
 * <p>The catalogue registered eighteen nodes with the server and twelve of them were read nowhere.
 * They reached every permission plugin's list, they carried a description and a default, and an
 * operator could take {@code uxmskyblock.island.create} off a group, see it taken off, and watch
 * that group go on making islands.
 *
 * <p>Every one of these ships as true, so a server that grants nothing by hand sees no change. What
 * changed is that taking one away does something.
 */
class APermissionTakenAwayClosesTheVerbTest {

    /** Each published verb permission, and a line that cannot be typed without it. */
    private static List<Object[]> gatedVerbs() {
        return List.of(
                new Object[] {CatalogPermissions.ISLAND_CREATE, "island create"},
                new Object[] {CatalogPermissions.ISLAND_DELETE, "island delete"},
                new Object[] {CatalogPermissions.ISLAND_HOME, "island home"},
                new Object[] {CatalogPermissions.ISLAND_SET_HOME, "island sethome base"},
                new Object[] {CatalogPermissions.ISLAND_INVITE, "island invite Somebody"},
                new Object[] {CatalogPermissions.ISLAND_KICK, "island kick Somebody"},
                new Object[] {CatalogPermissions.ISLAND_BAN, "island ban Somebody"},
                new Object[] {CatalogPermissions.ISLAND_UNBAN, "island unban Somebody"},
                new Object[] {CatalogPermissions.ISLAND_BANK, "island bank balance"},
                new Object[] {CatalogPermissions.ISLAND_BIOME, "island biome plains"},
                new Object[] {CatalogPermissions.ISLAND_TOP, "island top"},
                new Object[] {CatalogPermissions.ISLAND_UPGRADE, "island upgrades list"});
    }

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

    /** A sender who holds everything except {@code without}, or everything when that is null. */
    private static CommandSourceStack senderWithout(String without) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.hasPermission(without)).thenReturn(false);
        when(sender.isOp()).thenReturn(false);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    private static boolean runs(CommandDispatcher<CommandSourceStack> dispatcher, String line, String without) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(line, senderWithout(without));
        return parsed.getReader().getRemaining().isEmpty()
                && !parsed.getContext().getNodes().isEmpty()
                && parsed.getContext().getNodes().getLast().getNode().getCommand() != null;
    }

    @Test
    @DisplayName("A verb the catalogue names is closed to a sender who does not hold its node")
    void everyverbIsClosedWithoutItsNode() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        for (Object[] gated : gatedVerbs()) {
            CatalogPermissions permission = (CatalogPermissions) gated[0];
            String line = (String) gated[1];

            assertThat(runs(dispatcher, line, "nothing-is-taken-away"))
                    .describedAs("\"%s\" with every permission", line)
                    .isTrue();
            assertThat(runs(dispatcher, line, permission.node()))
                    .describedAs("\"%s\" without %s", line, permission.node())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Taking one node away closes that verb and no other")
    void takingOneNodeAwayClosesOneVerb() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        assertThat(runs(dispatcher, "island create", CatalogPermissions.ISLAND_CREATE.node()))
                .isFalse();
        assertThat(runs(dispatcher, "island top", CatalogPermissions.ISLAND_CREATE.node()))
                .describedAs("the board is nobody's business but its own node's")
                .isTrue();
    }

    @Test
    @DisplayName("The reload verb answers to the node the catalogue publishes for reloading")
    void thereloadNodeOpensTheReload() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(false);
        when(sender.hasPermission(CatalogPermissions.ADMIN_RELOAD.node())).thenReturn(true);
        when(sender.isOp()).thenReturn(false);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);

        ParseResults<CommandSourceStack> parsed = dispatcher.parse("island reload", source);

        assertThat(parsed.getContext().getNodes().getLast().getNode().getCommand())
                .describedAs("the gate asked for the general management node instead, so granting "
                        + "somebody the reload permission and nothing else granted them nothing")
                .isNotNull();
    }
}
