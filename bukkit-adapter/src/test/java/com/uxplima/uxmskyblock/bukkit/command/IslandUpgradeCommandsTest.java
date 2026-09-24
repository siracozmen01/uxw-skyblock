package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is upgrades}, end to end through Brigadier.
 *
 * <p>The island upgrades menu's every click ran {@code is upgrades buy <key>}, and that command had
 * never been written. A player clicking the size slot got the server's unknown command message, and
 * no island on any server had ever bought an upgrade.
 */
class IslandUpgradeCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final ServerNodeId NODE = ServerNodeId.of("node-1");

    private ServerMock server;
    private PlayerMock player;
    private IslandLocationService locations;
    private IslandUpgradeService upgrades;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private PlayerSessionCoordinator sessions;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        Map<UpgradeId, UpgradeDefinition> definitions =
                UpgradesConfiguration.defaultConfiguration().definitions();
        upgrades = mock(IslandUpgradeService.class);
        when(upgrades.definitions()).thenReturn(definitions);
        when(upgrades.getDefinition(any()))
                .thenAnswer(invocation -> Optional.ofNullable(definitions.get(invocation.getArgument(0))));
        when(upgrades.getCurrentTier(any(), any())).thenReturn(0);
        when(upgrades.purchaseUpgrade(any(), any(), any(), any(ServerNodeId.class)))
                .thenReturn(new UpgradePurchaseOutcome.Success(UpgradeId.SIZE, 1, 0L));

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandUpgradeCommands commands = new IslandUpgradeCommands(
                () -> upgrades,
                locations,
                inlineScheduler(),
                NODE,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    /** Puts the caller on the island under a role holding exactly these permissions. */
    private void callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission... permissions) {
        com.uxplima.uxmskyblock.core.domain.island.Island island =
                com.uxplima.uxmskyblock.core.domain.island.Island.create(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(0, 0, 64),
                        new PlayerUuid(java.util.UUID.randomUUID()),
                        new ProfileId(java.util.UUID.randomUUID()),
                        java.time.Instant.now());
        com.uxplima.uxmskyblock.core.domain.island.IslandRole role =
                new com.uxplima.uxmskyblock.core.domain.island.IslandRole(
                        "CUSTOM",
                        400,
                        "Custom",
                        permissions.length == 0
                                ? java.util.EnumSet.noneOf(
                                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission.class)
                                : java.util.EnumSet.of(permissions[0], permissions),
                        false);
        when(locations.findIsland(ISLAND))
                .thenReturn(Optional.of(island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                        new PlayerUuid(player.getUniqueId()), PROFILE, role, java.time.Instant.now()))));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A role that may not spend the island bank cannot buy an upgrade with it")
    void arolethatCannotSpendCannotBuy() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_DEPOSIT);

        run("upgrades buy island_size", player);

        verify(upgrades, never()).purchaseUpgrade(any(), any(), any(), any(ServerNodeId.class));
        assertThat(player.nextMessage()).describedAs("and is told why").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A purchase is told with the upgrade's name and its price as money, not the key and a count")
    void aPurchaseIsToldAsThePlayerReadsIt() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);
        when(upgrades.purchaseUpgrade(any(), any(), any(), any(ServerNodeId.class)))
                .thenReturn(new UpgradePurchaseOutcome.Success(UpgradeId.SIZE, 2, 50_000L));

        // The catalogue a server ships, rather than the bare keys the rest of this class reads.
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new IslandUpgradeCommands(
                        () -> upgrades, locations, inlineScheduler(), NODE, Messages.bundled(), sessions)
                .build());

        run("upgrades buy island_size", player);

        String told = String.valueOf(player.nextMessage());
        assertThat(told).contains("Island Size").contains("500.00").doesNotContain("ISLAND_SIZE");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("The activity feed keeps the upgrade's catalogue name, so each reader reads their own")
    void theFeedKeepsTheName() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);
        com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService feed =
                mock(com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService.class);
        IslandUpgradeCommands commands = new IslandUpgradeCommands(
                () -> upgrades, locations, inlineScheduler(), NODE, Messages.bundled(), sessions);
        commands.useActivityFeed(feed);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());

        run("upgrades buy island_size", player);

        verify(feed)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("activity.upgrade_purchased"),
                        org.mockito.ArgumentMatchers.argThat(
                                values -> "@upgrades.names.island_size".equals(values.get("key"))));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A role that may spend the island bank buys an upgrade that asks for nothing more")
    void arolethatCanSpendBuys() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);

        run("upgrades buy island_size", player);

        verify(upgrades).purchaseUpgrade(ISLAND, UpgradeId.SIZE, player.getUniqueId(), NODE);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An upgrade whose file names a permission asks for that one too")
    void anupgradeThatNamesAPermissionAsksForIt() throws Exception {
        UpgradeId spawner = UpgradeId.of("spawner_rates");
        when(upgrades.getDefinition(spawner))
                .thenReturn(Optional.of(new UpgradeDefinition(
                        spawner,
                        "Spawner Rates",
                        java.util.List.of(),
                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission.SPAWNER_UPGRADE)));
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);

        run("upgrades buy spawner_rates", player);

        verify(upgrades, never()).purchaseUpgrade(any(), any(), any(), any(ServerNodeId.class));

        callerHolds(
                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW,
                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.SPAWNER_UPGRADE);

        run("upgrades buy spawner_rates", player);

        verify(upgrades).purchaseUpgrade(ISLAND, spawner, player.getUniqueId(), NODE);
    }

    @Test
    @DisplayName("The bare verb lists every upgrade the operator's file defines")
    void theBareVerbListsEveryUpgrade() throws Exception {
        run("upgrades", player);

        int defined = UpgradesConfiguration.defaultConfiguration().definitions().size();
        // A header and one line per upgrade.
        for (int line = 0; line < defined + 1; line++) {
            assertThat(player.nextMessage())
                    .describedAs("line %d of the upgrade list", line)
                    .isNotNull();
        }
        assertThat(player.nextMessage()).describedAs("nothing after the list").isNull();
    }

    @Test
    @DisplayName("Buying names the caller's island, the key and this node")
    void buyingReachesTheService() throws Exception {
        run("upgrades buy island_size", player);

        verify(upgrades).purchaseUpgrade(ISLAND, UpgradeId.SIZE, player.getUniqueId(), NODE);
        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("The key the shipped menu clicks is a key the command accepts")
    void theMenuKeyIsAccepted() throws Exception {
        run("upgrades buy member_limit", player);

        verify(upgrades).purchaseUpgrade(ISLAND, UpgradeId.MEMBERS, player.getUniqueId(), NODE);
    }

    @Test
    @DisplayName("A key no upgrade uses is refused before the bank is touched")
    void anUnknownKeyIsRefusedWithoutCharging() throws Exception {
        run("upgrades buy nonsense", player);

        verify(upgrades, never()).purchaseUpgrade(any(), any(), any(), any(ServerNodeId.class));
        assertThat(player.nextMessage()).describedAs("the player is told why").isNotNull();
    }

    @Test
    @DisplayName("Every upgrade the shipped configuration defines can be named on the command line")
    void everyDefinedUpgradeCanBeBought() throws Exception {
        for (UpgradeId upgradeId : UpgradeId.builtIn()) {
            run("upgrades buy " + upgradeId.key(), player);
            verify(upgrades).purchaseUpgrade(ISLAND, upgradeId, player.getUniqueId(), NODE);
        }
    }
}
