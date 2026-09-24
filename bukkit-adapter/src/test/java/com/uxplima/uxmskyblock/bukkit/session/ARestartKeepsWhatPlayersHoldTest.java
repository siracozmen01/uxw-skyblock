package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A server stopped with players online keeps what they hold.
 *
 * <p>Shutdown asked each player's own thread for their inventory and read the answer at once. The
 * plugin is already disabled when shutdown runs, so the scheduler dropped the task, and an empty
 * inventory was written over every player online at the restart. Seen on the lab server: five
 * diamonds a checkpoint had kept were gone after a stop. What the player holds is now read on the
 * thread that owns them; where nothing can be read, nothing is written and the last checkpoint stands.
 */
class ARestartKeepsWhatPlayersHoldTest extends MockBukkitHarness {

    private PersistenceBootstrap persistence;
    private Plugin plugin;

    @BeforeEach
    void setUpPersistence() throws Exception {
        Path dir = Files.createTempDirectory("restart_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("restart.db"));
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDownPersistence() {
        persistence.close();
    }

    @Test
    @DisplayName("A stop with the player online writes what they hold")
    void aStopWritesWhatThePlayerHolds() {
        PlayerSessionCoordinator coordinator = coordinator(new FoliaSchedulerAdapter(plugin));
        PlayerMock player = joined(coordinator, "Holder");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));

        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
        coordinator.shutdown();

        assertThat(stored(player)).containsExactly(new ItemStack(Material.DIAMOND, 5));
        assertThat(persistence
                        .sessionAuthorityPort()
                        .findSession(new PlayerUuid(player.getUniqueId()))
                        .orElseThrow()
                        .state())
                .isEqualTo(SessionState.OFFLINE);
    }

    @Test
    @DisplayName("A stop that cannot read the inventory writes nothing over the last checkpoint")
    void aStopThatCannotReadWritesNothing() {
        SchedulerPort real = new FoliaSchedulerAdapter(plugin);
        SchedulerPort foreign = new ForeignThread(real);
        PlayerSessionCoordinator coordinator = coordinator(foreign);
        PlayerMock player = joined(coordinator, "Stranger");
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 3));
        checkpointed(coordinator, player);
        player.getInventory().setItem(1, new ItemStack(Material.GOLD_INGOT, 2));

        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
        coordinator.shutdown();

        assertThat(stored(player))
                .describedAs("the last checkpoint, not an empty inventory")
                .containsExactly(new ItemStack(Material.EMERALD, 3));
    }

    private PlayerSessionCoordinator coordinator(SchedulerPort scheduler) {
        return new PlayerSessionCoordinator(
                ServerNodeId.of("restart-node"),
                persistence.sessionAuthorityPort(),
                persistence.inventoryPort(),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                scheduler,
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled());
    }

    /** Writes what the player holds as the ambient checkpoint does, and waits for nothing. */
    private void checkpointed(PlayerSessionCoordinator coordinator, PlayerMock player) {
        ActiveSession session = Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()));
        var outcome = persistence
                .inventoryPort()
                .checkpointInventory(
                        session.playerUuid(),
                        session.activeProfileId(),
                        ServerNodeId.of("restart-node"),
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        BukkitInventorySerializer.serializeItemStacks(
                                player.getInventory().getContents()));
        assertThat(outcome).isInstanceOf(ProfileInventoryMutationOutcome.Success.class);
        session.setLastDurableVersion(((ProfileInventoryMutationOutcome.Success) outcome).newVersion());
        assertThat(stored(player)).containsExactly(new ItemStack(Material.EMERALD, 3));
    }

    private PlayerMock joined(PlayerSessionCoordinator coordinator, String name) {
        PlayerMock player = createPlayer(name);
        coordinator.handlePlayerJoin(player);
        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());
        return player;
    }

    private ItemStack[] stored(PlayerMock player) {
        byte[] nbt = persistence
                .inventoryPort()
                .loadInventory(new ProfileId(player.getUniqueId()))
                .orElseThrow()
                .inventoryNbt();
        return Arrays.stream(BukkitInventorySerializer.deserializeItemStacks(nbt))
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .toArray(ItemStack[]::new);
    }

    /** Ticks the server until {@code assertion} holds, or gives up after five seconds with its last failure. */
    private void eventuallyTick(Runnable assertion) {
        long start = System.currentTimeMillis();
        AssertionError last = new AssertionError("never checked");
        while (System.currentTimeMillis() - start < 5000) {
            server.getScheduler().performOneTick();
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.onSpinWait();
            }
        }
        throw last;
    }

    /** A thread that owns no player: the real scheduler, answering no to every ownership question. */
    private record ForeignThread(SchedulerPort real) implements SchedulerPort {

        @Override
        public void onGlobal(Runnable task) {
            real.onGlobal(task);
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            real.onRegion(worldName, chunkX, chunkZ, task);
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            real.onEntity(playerUuid, task);
        }

        @Override
        public boolean ownsEntity(PlayerUuid playerUuid) {
            return false;
        }

        @Override
        public void async(Runnable task) {
            real.async(task);
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            real.asyncAfter(delay, task);
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            real.laterGlobal(delay, task);
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return real.repeatGlobal(task, initialDelay, period);
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return real.repeatAsync(task, initialDelay, period);
        }
    }
}
