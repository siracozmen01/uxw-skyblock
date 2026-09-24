package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
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
 * A move to another server that cannot go ahead either keeps the player here or stops them acting.
 *
 * <p>A session that cannot be drained was never given away, so the player stays and plays on. One
 * that was drained and whose state then could not be written is fenced: this server no longer
 * acts for it, and writing the state later could overwrite what the target writes.
 */
class AHandoffThatCannotGoAheadLeavesThePlayerSafeTest extends MockBukkitHarness {

    private static final ServerNodeId HERE = ServerNodeId.of("node-here");
    private static final ServerNodeId THERE = ServerNodeId.of("node-there");

    @SuppressWarnings("NullAway.Init")
    private PersistenceBootstrap persistence;

    @SuppressWarnings("NullAway.Init")
    private PlayerSessionCoordinator coordinator;

    @BeforeEach
    void setUpPersistence() throws Exception {
        persistence = PersistenceBootstrap.createSqlite(
                Files.createTempDirectory("handoff_refused_").resolve("handoff.db"));
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) { // a test that failed before it started one
            coordinator.shutdown();
        }
        persistence.close();
    }

    @Test
    @DisplayName("A session the database will not drain stays here, and the player plays on")
    void anUndrainedSessionStays() {
        start(
                refusing(
                        persistence.sessionAuthorityPort(),
                        PlayerSessionAuthorityPort.class,
                        "drain",
                        SessionAuthorityOutcome.rejected()),
                persistence.handoffFinalizationPort());
        PlayerMock player = inPlay("Stays");

        assertThat(handOff(player)).isFalse();

        assertThat(coordinator.inPlay(player.getUniqueId())).isTrue();
        assertThat(persistence
                        .sessionAuthorityPort()
                        .findSession(new PlayerUuid(player.getUniqueId()))
                        .orElseThrow()
                        .state())
                .isEqualTo(SessionState.ACTIVE);
    }

    @Test
    @DisplayName("A drained session whose state cannot be written is fenced, not handed on and not kept")
    void anUnwrittenStateFences() {
        start(
                persistence.sessionAuthorityPort(),
                refusing(
                        persistence.handoffFinalizationPort(),
                        ProfileHandoffFinalizationPort.class,
                        "finalizeHandoffFlush",
                        ProfileInventoryMutationOutcome.rejected()));
        PlayerMock player = inPlay("Fenced");

        assertThat(handOff(player)).isFalse();

        assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();
        assertThat(coordinator.inPlay(player.getUniqueId())).isFalse();
        assertThat(persistence
                        .sessionAuthorityPort()
                        .findSession(new PlayerUuid(player.getUniqueId()))
                        .orElseThrow()
                        .state())
                .describedAs("never readied for the target")
                .isNotEqualTo(SessionState.HANDOFF_READY);
    }

    @Test
    @DisplayName("A written state whose handoff cannot be readied is fenced too, so the target is never raced")
    void anUnreadiedHandoffFences() {
        start(
                refusing(
                        persistence.sessionAuthorityPort(),
                        PlayerSessionAuthorityPort.class,
                        "prepareHandoff",
                        SessionAuthorityOutcome.rejected()),
                persistence.handoffFinalizationPort());
        PlayerMock player = inPlay("Unreadied");

        assertThat(handOff(player)).isFalse();

        assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();
        assertThat(coordinator.inPlay(player.getUniqueId())).isFalse();
    }

    private void start(PlayerSessionAuthorityPort sessions, ProfileHandoffFinalizationPort finalization) {
        coordinator = new PlayerSessionCoordinator(
                HERE,
                sessions,
                persistence.inventoryPort(),
                finalization,
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                new FoliaSchedulerAdapter(MockBukkit.createMockPlugin()),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled());
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

    /** {@code real}, answering {@code method} with {@code answer} and every other call as it would. */
    private static <T> T refusing(T real, Class<T> type, String method, Object answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) -> {
            if (called.getName().equals(method)) {
                return answer;
            }
            try {
                return called.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }
}
