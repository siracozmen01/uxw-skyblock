package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A move to another server writes the player's state and readies the session there, at once.
 *
 * <p>The testing standard names this test. The ambient checkpoint here runs once an hour, so whatever
 * reaches the database comes from the handoff itself. A move used to leave the session where it was,
 * and the state was written whenever the quit on this server got to it; the next server found the
 * session still held. The handoff now writes the whole state, readies the session for the target,
 * and the target takes it as the player arrives.
 */
class HandoffFinalFlushDoesNotWaitForAmbientCheckpointTest extends MockBukkitHarness {

    private static final ServerNodeId HERE = ServerNodeId.of("node-here");
    private static final ServerNodeId THERE = ServerNodeId.of("node-there");

    private Path database;
    private PersistenceBootstrap persistence;
    private PlayerSessionCoordinator coordinator;

    @BeforeEach
    void setUpCoordinator() throws Exception {
        database = Files.createTempDirectory("handoff_").resolve("handoff.db");
        persistence = PersistenceBootstrap.createSqlite(database);
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
                Messages.bundled());
    }

    @AfterEach
    void tearDownCoordinator() {
        coordinator.shutdown();
        persistence.close();
    }

    @Test
    @DisplayName("A handoff writes the whole state and readies the session, and the target takes it on arrival")
    void theHandoffWritesAndReadies() {
        PlayerMock player = inPlay("Mover");
        long epoch = Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()))
                .sessionEpoch();
        player.getInventory().setItem(0, new ItemStack(Material.NETHERITE_INGOT, 3));
        player.setLevel(7);

        assertThat(handOff(player)).isTrue();

        PlayerSessionRecord readied = session(player);
        assertThat(readied.state()).isEqualTo(SessionState.HANDOFF_READY);
        assertThat(readied.handoffTargetNode()).isEqualTo(THERE);
        ProfileInventoryRecord written = stored(player);
        assertThat(items(written)).containsExactly(new ItemStack(Material.NETHERITE_INGOT, 3));
        assertThat(written.experiencePoints()).describedAs("seven levels").isEqualTo(91);
        assertThat(coordinator.inPlay(player.getUniqueId()))
                .describedAs("nothing is done between the handoff and the move")
                .isFalse();

        SessionAuthorityOutcome arrived = persistence
                .sessionAuthorityPort()
                .ensureSession(new PlayerUuid(player.getUniqueId()), new ProfileId(player.getUniqueId()), THERE);
        assertThat(arrived).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1, false));
    }

    @Test
    @DisplayName("A player the proxy never moved gets the session back once the handoff has lapsed")
    void aPlayerLeftBehindGetsItBack() throws Exception {
        PlayerMock player = inPlay("Stayer");
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 4));
        assertThat(handOff(player)).isTrue();

        lapse(player);
        coordinator.reclaimIfStillHere(player);

        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.inPlay(player.getUniqueId())).isTrue();
        });
        assertThat(session(player).state()).isEqualTo(SessionState.ACTIVE);
        assertThat(session(player).authoritativeNode()).isEqualTo(HERE);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.EMERALD, 4));
    }

    @Test
    @DisplayName("There is no handoff to the server the player is already on")
    void noHandoffToItself() {
        PlayerMock player = inPlay("Here");

        assertThat(coordinator.handOff(player, HERE).join()).isFalse();
        assertThat(coordinator.inPlay(player.getUniqueId())).isTrue();
    }

    private boolean handOff(PlayerMock player) {
        var answer = coordinator.handOff(player, THERE);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(answer).isDone();
        });
        return answer.join();
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

    private PlayerSessionRecord session(PlayerMock player) {
        return persistence
                .sessionAuthorityPort()
                .findSession(new PlayerUuid(player.getUniqueId()))
                .orElseThrow();
    }

    private ProfileInventoryRecord stored(PlayerMock player) {
        return persistence
                .inventoryPort()
                .loadInventory(new ProfileId(player.getUniqueId()))
                .orElseThrow();
    }

    private static ItemStack[] items(ProfileInventoryRecord record) {
        return Arrays.stream(BukkitInventorySerializer.deserializeItemStacks(record.inventoryNbt()))
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .toArray(ItemStack[]::new);
    }

    /** The handoff's lease runs out, the way it does when its target never takes it. */
    private void lapse(PlayerMock player) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + database);
                PreparedStatement ps = conn.prepareStatement("UPDATE player_sessions SET"
                        + " lease_expires_at = DATETIME('now', '-30 seconds'),"
                        + " handoff_expires_at = DATETIME('now', '-30 seconds') WHERE player_uuid = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }
}
