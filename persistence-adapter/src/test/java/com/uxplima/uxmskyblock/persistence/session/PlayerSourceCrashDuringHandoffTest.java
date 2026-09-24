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
 * A source node that died in the middle of a handoff keeps the player until its lease runs out.
 *
 * <p>The testing standard names this test. Node A starts draining the player and stops there. The
 * player reaches node B, which is refused while node A's lease still stands, so the player waits. When
 * the lease runs out node B takes the player at the next epoch and loads the last inventory node A
 * made durable.
 */
class PlayerSourceCrashDuringHandoffTest {

    @TempDir
    Path dir;

    private OnePlayerAcrossNodes scene;

    @BeforeEach
    void setUp() {
        scene = new OnePlayerAcrossNodes(dir);
    }

    @AfterEach
    void tearDown() {
        scene.close();
    }

    @Test
    @DisplayName("The destination waits out the source's lease, then takes the player at the next epoch")
    void theDestinationWaitsOutTheLease() throws Exception {
        long epoch = scene.loggedIn(NODE_A);
        scene.flushed(NODE_A, epoch, "durable-on-a");
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();
        // Node A stops here.

        assertThat(scene.login(NODE_B).isSuccess())
                .describedAs("node B while node A's lease stands")
                .isFalse();
        assertThat(scene.session().state()).isEqualTo(SessionState.DRAINING);
        assertThat(scene.session().authoritativeNode()).isEqualTo(NODE_A);

        scene.leaseRunsOut();

        assertThat(scene.login(NODE_B)).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1, true));
        assertThat(scene.session().authoritativeNode()).isEqualTo(NODE_B);
        assertThat(scene.inventory()).isEqualTo("durable-on-a");
    }
}
