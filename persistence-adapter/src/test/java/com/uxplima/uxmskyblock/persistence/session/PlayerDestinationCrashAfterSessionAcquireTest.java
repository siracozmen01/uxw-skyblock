package com.uxplima.uxmskyblock.persistence.session;

import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_A;
import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_B;
import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_C;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A destination node that died right after taking the player is recovered from by the next node.
 *
 * <p>The testing standard names this test. Node A hands the player to node B, and node B dies before
 * it writes anything. The player reaches node C, which waits out node B's lease, takes the player two
 * epochs past node A and loads the inventory node A handed over. Node B, were it to come back, can no
 * longer write.
 */
class PlayerDestinationCrashAfterSessionAcquireTest {

    private static final String HANDOFF = "handoff-1";

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
    @DisplayName("The next node waits out the dead destination and loads what the source handed over")
    void theNextNodeRecovers() throws Exception {
        long epoch = scene.loggedIn(NODE_A);
        scene.flushed(NODE_A, epoch, "handed-over-by-a");
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();
        assertThat(scene.sessions
                        .prepareHandoff(scene.player, NODE_A, epoch, HANDOFF, NODE_B)
                        .isSuccess())
                .isTrue();
        assertThat(scene.sessions.plannedAcquire(scene.player, NODE_A, epoch, HANDOFF, NODE_B))
                .isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1));
        long readByB = scene.version();
        // Node B stops here, before it writes anything.

        assertThat(scene.login(NODE_C).isSuccess())
                .describedAs("node C while node B's lease stands")
                .isFalse();

        scene.leaseRunsOut();

        assertThat(scene.login(NODE_C)).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 2, true));
        assertThat(scene.inventory()).isEqualTo("handed-over-by-a");
        assertThat(scene.flush(NODE_B, epoch + 1, readByB, "late-on-b").isSuccess())
                .describedAs("node B coming back")
                .isFalse();
        assertThat(scene.inventory()).isEqualTo("handed-over-by-a");
    }
}
