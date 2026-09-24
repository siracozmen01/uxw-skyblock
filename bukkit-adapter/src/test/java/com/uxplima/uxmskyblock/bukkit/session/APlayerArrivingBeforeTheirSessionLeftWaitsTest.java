package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player who reaches this server before the last one has let their session go waits, and gets in.
 *
 * <p>A proxy connects the player to the next server before the last one has finished its final write.
 * The join found the session still held elsewhere and kicked the player, so every move between
 * servers could end on the login screen. The player is now told to wait and let in as soon as the
 * other server releases the session; only a session nobody releases within the longest lease there
 * is turns them away.
 */
class APlayerArrivingBeforeTheirSessionLeftWaitsTest extends MockBukkitHarness {

    private static final ServerNodeId HERE = ServerNodeId.of("node-here");
    private static final ServerNodeId THERE = ServerNodeId.of("node-there");

    private final AtomicLong clock = new AtomicLong(5_000_000_000L);
    private PersistenceBootstrap persistence;
    private PlayerSessionCoordinator coordinator;

    @BeforeEach
    void setUpCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("arriving_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("arriving.db"));
        coordinator = new PlayerSessionCoordinator(
                HERE,
                persistence.sessionAuthorityPort(),
                persistence.inventoryPort(),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                new FoliaSchedulerAdapter(MockBukkit.createMockPlugin()),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled(),
                clock::get);
    }

    @AfterEach
    void tearDownCoordinator() {
        coordinator.shutdown();
        persistence.close();
    }

    @Test
    @DisplayName("A player whose session is still closing elsewhere is told to wait and let in once it closes")
    void waitsAndGetsIn() {
        PlayerMock player = createPlayer("Traveller");
        long thereEpoch = heldThere(player);

        coordinator.handlePlayerJoin(player);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(String.valueOf(player.nextMessage())).contains("still closing on another server");
        });
        assertThat(player.isOnline()).describedAs("not turned away").isTrue();
        assertThat(coordinator.inPlay(player.getUniqueId())).isFalse();

        // The other server finishes its final write and lets the session go.
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        assertThat(persistence
                        .sessionAuthorityPort()
                        .drain(uuid, THERE, thereEpoch)
                        .isSuccess())
                .isTrue();
        assertThat(persistence
                        .sessionAuthorityPort()
                        .releaseToOffline(uuid, THERE, thereEpoch)
                        .isSuccess())
                .isTrue();

        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.inPlay(player.getUniqueId())).isTrue();
        });
        assertThat(player.isOnline()).isTrue();
    }

    @Test
    @DisplayName("A player whose session nobody releases is turned away once the longest lease has passed")
    void turnedAwayAtLast() {
        PlayerMock player = createPlayer("Stuck");
        heldThere(player);

        coordinator.handlePlayerJoin(player);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(String.valueOf(player.nextMessage())).contains("still closing on another server");
        });

        clock.addAndGet(PlayerSessionCoordinator.LOGIN_WAIT.toNanos());
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(player.isOnline()).isFalse();
        });
    }

    /** The other server holds the player's session, with its lease running. */
    private long heldThere(PlayerMock player) {
        SessionAuthorityOutcome there = persistence
                .sessionAuthorityPort()
                .ensureSession(new PlayerUuid(player.getUniqueId()), new ProfileId(player.getUniqueId()), THERE);
        return ((SessionAuthorityOutcome.Success) there).epoch();
    }
}
