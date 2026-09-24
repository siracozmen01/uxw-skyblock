package com.uxplima.uxmskyblock.persistence.session;

import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_A;
import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node that paused long enough to lose the player cannot write the player's inventory when it wakes.
 *
 * <p>The testing standard names this test. Node A reads the inventory at its epoch and stalls, a long
 * garbage collection as far as anyone can tell. Its lease runs out, node B takes the player at the
 * next epoch and writes. Node A wakes and flushes what it read: the write is refused and node B's
 * inventory stands. A lease that ran out with nobody taking over refuses the write just the same.
 */
class StalePlayerAsyncFlushRejectedTest {

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
    @DisplayName("A flush from the epoch before a takeover is refused and the new node's inventory stands")
    void aStaleEpochIsRefused() throws Exception {
        long epoch = scene.loggedIn(NODE_A);
        scene.flushed(NODE_A, epoch, "on-a");
        long readByA = scene.version();

        scene.leaseRunsOut();
        SessionAuthorityOutcome takeover = scene.login(NODE_B);
        assertThat(takeover).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1, true));
        assertThat(scene.sessions
                        .markRecoveredActive(scene.player, NODE_B, epoch + 1)
                        .isSuccess())
                .isTrue();
        scene.flushed(NODE_B, epoch + 1, "on-b");

        assertThat(scene.flush(NODE_A, epoch, readByA, "stale-on-a").isSuccess())
                .describedAs("node A waking up")
                .isFalse();
        assertThat(scene.flush(NODE_A, epoch, scene.version(), "stale-on-a").isSuccess())
                .describedAs("node A, even had it read the latest version")
                .isFalse();
        assertThat(scene.inventory()).isEqualTo("on-b");
    }

    @Test
    @DisplayName("A flush after the lease ran out is refused even with nobody holding the player")
    void anExpiredLeaseIsRefused() throws Exception {
        long epoch = scene.loggedIn(NODE_A);
        scene.flushed(NODE_A, epoch, "on-a");

        scene.leaseRunsOut();

        assertThat(scene.flush(NODE_A, epoch, scene.version(), "after-the-lease")
                        .isSuccess())
                .isFalse();
        assertThat(scene.inventory()).isEqualTo("on-a");
    }
}
