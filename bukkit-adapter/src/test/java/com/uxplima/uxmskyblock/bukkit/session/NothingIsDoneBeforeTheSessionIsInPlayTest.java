package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player does nothing with what they hold until their session's state is in their hands.
 *
 * <p>Nothing stopped a player between the join and the moment their durable state was put on them,
 * or after this server fenced itself off their session. What they held was about to be replaced or
 * could no longer be written, and an item dropped then stayed on the ground and came back with the
 * state as well.
 */
class NothingIsDoneBeforeTheSessionIsInPlayTest extends MockBukkitHarness {

    private PersistenceBootstrap persistence;
    private PlayerSessionCoordinator coordinator;
    private PlayerSessionListener listener;

    @BeforeEach
    void setUpCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("inplay_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("inplay.db"));
        coordinator = new PlayerSessionCoordinator(
                ServerNodeId.of("inplay-node"),
                persistence.sessionAuthorityPort(),
                persistence.inventoryPort(),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                new FoliaSchedulerAdapter(MockBukkit.createMockPlugin()),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled());
        listener = new PlayerSessionListener(coordinator);
    }

    @AfterEach
    void tearDownCoordinator() {
        coordinator.shutdown();
        persistence.close();
    }

    @Test
    @DisplayName("A drop and a break are refused before the join has given the player their state")
    void refusedBeforeTheJoin() {
        PlayerMock player = createPlayer("Early");

        assertThat(dropped(player)).isFalse();
        assertThat(broke(player)).isFalse();
    }

    @Test
    @DisplayName("A drop and a break go through once the session is in play")
    void allowedInPlay() {
        PlayerMock player = inPlay("Playing");

        assertThat(dropped(player)).isTrue();
        assertThat(broke(player)).isTrue();
    }

    @Test
    @DisplayName("A drop and a break are refused once this server has fenced the session")
    void refusedOnceFenced() {
        PlayerMock player = inPlay("Fenced");

        coordinator.selfFencePlayer(new PlayerUuid(player.getUniqueId()), "the database stopped answering");

        assertThat(dropped(player)).isFalse();
        assertThat(broke(player)).isFalse();
    }

    private PlayerMock inPlay(String name) {
        PlayerMock player = createPlayer(name);
        coordinator.handlePlayerJoin(player);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.inPlay(player.getUniqueId())).isTrue();
        });
        return player;
    }

    /** Whether a drop by {@code player} went through. */
    private boolean dropped(PlayerMock player) {
        Item item = player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.DIAMOND));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        listener.onDrop(event);
        return !event.isCancelled();
    }

    /** Whether a block break by {@code player} went through. */
    private boolean broke(PlayerMock player) {
        BlockBreakEvent event = new BlockBreakEvent(player.getLocation().getBlock(), player);
        listener.onBreak(event);
        return !event.isCancelled();
    }
}
