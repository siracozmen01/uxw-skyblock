package com.uxplima.uxmskyblock.persistence.session;

import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_A;
import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An ambient checkpoint prepared while the session was active does not write once it is not.
 *
 * <p>The testing standard names this test. Node A reads the inventory while it holds the player, and
 * before its checkpoint runs the session moves on: it drains, it is readied for a handoff, it is
 * recovering after a lease ran out, it is released. Each time the checkpoint is refused and the
 * inventory is left as it was. Node A holds the same epoch throughout, so nothing but the state
 * refuses it; and where the session comes back to active, the same write goes through.
 */
class AmbientCheckpointRejectedAfterSessionLeavesActiveTest {

    @TempDir
    Path dir;

    private OnePlayerAcrossNodes scene;
    private long epoch;
    private long version;

    @BeforeEach
    void setUp() {
        scene = new OnePlayerAcrossNodes(dir);
        epoch = scene.loggedIn(NODE_A);
        scene.flushed(NODE_A, epoch, "durable");
        version = scene.version();
    }

    @AfterEach
    void tearDown() {
        scene.close();
    }

    @Test
    @DisplayName("A draining session refuses the checkpoint")
    void draining() {
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();

        refused(epoch, SessionState.DRAINING);
    }

    @Test
    @DisplayName("A session readied for a handoff refuses the checkpoint")
    void handoffReady() {
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();
        assertThat(scene.sessions
                        .prepareHandoff(scene.player, NODE_A, epoch, "handoff-1", NODE_B)
                        .isSuccess())
                .isTrue();

        refused(epoch, SessionState.HANDOFF_READY);
    }

    @Test
    @DisplayName("A recovering session refuses the checkpoint until it is active again")
    void recovering() throws Exception {
        scene.leaseRunsOut();
        assertThat(scene.login(NODE_A)).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1, true));

        refused(epoch + 1, SessionState.RECOVERING);

        assertThat(scene.sessions
                        .markRecoveredActive(scene.player, NODE_A, epoch + 1)
                        .isSuccess())
                .isTrue();
        assertThat(scene.flush(NODE_A, epoch + 1, version, "checkpointed").isSuccess())
                .describedAs("the same write once the session is active")
                .isTrue();
    }

    @Test
    @DisplayName("A released session refuses the checkpoint")
    void offline() {
        // A session is released the way a quit releases it: drained first.
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();
        assertThat(scene.sessions.releaseToOffline(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();

        refused(epoch, SessionState.OFFLINE);
    }

    private void refused(long heldEpoch, SessionState state) {
        assertThat(scene.session().state()).isEqualTo(state);
        assertThat(scene.session().sessionEpoch()).isEqualTo(heldEpoch);
        assertThat(scene.flush(NODE_A, heldEpoch, version, "checkpointed").isSuccess())
                .describedAs("a checkpoint once the session is %s", state)
                .isFalse();
        assertThat(scene.inventory()).isEqualTo("durable");
        assertThat(scene.version()).isEqualTo(version);
    }
}
