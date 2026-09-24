package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.bukkit.Material;
import org.bukkit.entity.Item;
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
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionLease;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A node stops acting on a player's lease before the database could hand it to someone else.
 *
 * <p>The testing standard names this test. The node counts the lease on its own monotonic clock from
 * the moment it asked for it, less a margin. Past that deadline nothing the player does goes through,
 * whether or not the database has answered: a node whose database went quiet kept letting its player
 * drop and trade while another node took the session. A renewal that answers after the deadline, and
 * one that is refused, fence the session and move the player on.
 */
class PlayerAuthorityLocalSelfFenceTest extends MockBukkitHarness {

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private PersistenceBootstrap persistence;

    @SuppressWarnings("NullAway.Init")
    private PlayerSessionCoordinator coordinator;

    @SuppressWarnings("NullAway.Init")
    private PlayerSessionListener listener;

    @BeforeEach
    void setUpPersistence() throws Exception {
        Path dir = Files.createTempDirectory("selffence_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("selffence.db"));
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) { // a test that failed before it started one
            coordinator.shutdown();
        }
        persistence.close();
    }

    @Test
    @DisplayName("Past the local deadline a quiet database stops the player acting, before its lease is over")
    void theDeadlinePassesQuietly() {
        start(persistence.sessionAuthorityPort(), Duration.ofHours(1));
        PlayerMock player = inPlay("Quiet");
        assertThat(dropped(player)).isTrue();

        clock.addAndGet(SessionLease.locallyHeld().toNanos() - 1);
        assertThat(dropped(player)).describedAs("just before the deadline").isTrue();

        clock.addAndGet(1);
        assertThat(SessionLease.locallyHeld()).isLessThan(SessionLease.ACTIVE);
        assertThat(dropped(player))
                .describedAs("at the deadline, with the database lease still running")
                .isFalse();
    }

    @Test
    @DisplayName("A renewal answered after the local deadline fences the session and moves the player on")
    void aLateAnswerFences() {
        PlayerSessionAuthorityPort late = renewing(persistence.sessionAuthorityPort(), outcome -> {
            clock.addAndGet(SessionLease.locallyHeld().toNanos());
            return outcome;
        });
        start(late, Duration.ofMillis(50));
        PlayerMock player = inPlay("Late");

        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();
        });
        assertThat(dropped(player)).isFalse();
    }

    @Test
    @DisplayName("A refused renewal fences the session")
    void aRefusedRenewalFences() {
        start(
                renewing(persistence.sessionAuthorityPort(), outcome -> SessionAuthorityOutcome.rejected()),
                Duration.ofMillis(50));
        PlayerMock player = inPlay("Refused");

        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();
        });
        assertThat(dropped(player)).isFalse();
    }

    @Test
    @DisplayName("A renewal answered in time moves the deadline on from when it was asked")
    void aTimelyRenewalHolds() {
        start(persistence.sessionAuthorityPort(), Duration.ofMillis(50));
        PlayerMock player = inPlay("Timely");
        ActiveSession session = Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()));
        long first = session.heldUntilNanos();

        clock.addAndGet(Duration.ofSeconds(5).toNanos());
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(session.heldUntilNanos()).isGreaterThan(first);
        });
        assertThat(session.heldUntilNanos())
                .isEqualTo(clock.get() + SessionLease.locallyHeld().toNanos());
        assertThat(dropped(player)).isTrue();
    }

    private void start(PlayerSessionAuthorityPort sessions, Duration heartbeat) {
        coordinator = new PlayerSessionCoordinator(
                ServerNodeId.of("fence-node"),
                sessions,
                persistence.inventoryPort(),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                new FoliaSchedulerAdapter(MockBukkit.createMockPlugin()),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                heartbeat,
                Duration.ofHours(1),
                Messages.bundled(),
                clock::get);
        listener = new PlayerSessionListener(coordinator);
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

    private boolean dropped(PlayerMock player) {
        Item item = player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.DIAMOND));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        listener.onDrop(event);
        return !event.isCancelled();
    }

    /** {@code real}, with every renewal's answer passed through {@code answer}. */
    private static PlayerSessionAuthorityPort renewing(
            PlayerSessionAuthorityPort real, UnaryOperator<SessionAuthorityOutcome> answer) {
        return (PlayerSessionAuthorityPort) Proxy.newProxyInstance(
                PlayerSessionAuthorityPort.class.getClassLoader(),
                new Class<?>[] {PlayerSessionAuthorityPort.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(real, args);
                        return method.getName().equals("renew")
                                ? answer.apply((SessionAuthorityOutcome) result)
                                : result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
