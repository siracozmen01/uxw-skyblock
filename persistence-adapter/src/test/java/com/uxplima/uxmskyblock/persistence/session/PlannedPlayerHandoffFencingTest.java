package com.uxplima.uxmskyblock.persistence.session;

import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_A;
import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_B;
import static com.uxplima.uxmskyblock.persistence.session.OnePlayerAcrossNodes.NODE_C;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A planned handoff hands the player to the node it names, while the handoff lasts, and to no other.
 *
 * <p>The testing standard names this test. Node A drains the player, writes the last inventory and
 * readies the handoff for node B. Node C, which was not named, is refused and changes nothing. Node B
 * takes the player at the next epoch, and from then on node A's writes are refused. A handoff that
 * lapsed is refused too, and node B takes the player the way it would after a crash.
 */
class PlannedPlayerHandoffFencingTest {

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
    @DisplayName("Only the named destination takes the handoff, at the next epoch, and the source is fenced")
    void onlyTheNamedNodeTakesIt() {
        long epoch = handoffReady();

        assertThat(scene.sessions
                        .plannedAcquire(scene.player, NODE_A, epoch, HANDOFF, NODE_C)
                        .isSuccess())
                .describedAs("a node the handoff does not name")
                .isFalse();
        PlayerSessionRecord untouched = scene.session();
        assertThat(untouched.state()).isEqualTo(SessionState.HANDOFF_READY);
        assertThat(untouched.authoritativeNode()).isEqualTo(NODE_A);
        assertThat(untouched.sessionEpoch()).isEqualTo(epoch);

        SessionAuthorityOutcome taken = scene.sessions.plannedAcquire(scene.player, NODE_A, epoch, HANDOFF, NODE_B);
        assertThat(taken).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1));
        PlayerSessionRecord moved = scene.session();
        assertThat(moved.state()).isEqualTo(SessionState.ACTIVE);
        assertThat(moved.authoritativeNode()).isEqualTo(NODE_B);
        assertThat(moved.sessionEpoch()).isEqualTo(epoch + 1);
        assertThat(moved.handoffId()).isNull();
        assertThat(moved.handoffTargetNode()).isNull();
        assertThat(moved.handoffExpiresAt()).isNull();

        assertThat(scene.inventory()).isEqualTo("final-on-a");
        assertThat(scene.flush(NODE_A, epoch, scene.version(), "late-on-a").isSuccess())
                .describedAs("the source after the handoff")
                .isFalse();
        scene.flushed(NODE_B, epoch + 1, "first-on-b");
        assertThat(scene.inventory()).isEqualTo("first-on-b");
    }

    @Test
    @DisplayName("A lapsed handoff is refused, and the destination takes the player as after a crash")
    void aLapsedHandoffFallsBackToTakeover() throws Exception {
        long epoch = handoffReady();
        scene.leaseRunsOut();

        assertThat(scene.sessions
                        .plannedAcquire(scene.player, NODE_A, epoch, HANDOFF, NODE_B)
                        .isSuccess())
                .isFalse();
        assertThat(scene.session().sessionEpoch()).isEqualTo(epoch);

        SessionAuthorityOutcome takeover = scene.sessions.failureTakeover(scene.player, epoch, NODE_B);
        assertThat(takeover).isEqualTo(new SessionAuthorityOutcome.Success(epoch + 1, true));
        assertThat(scene.session().state()).isEqualTo(SessionState.RECOVERING);
        assertThat(scene.inventory())
                .describedAs("the last inventory node A wrote")
                .isEqualTo("final-on-a");
    }

    /** Node A holds the player, writes the last inventory, drains and readies the handoff to node B. */
    private long handoffReady() {
        long epoch = scene.loggedIn(NODE_A);
        scene.flushed(NODE_A, epoch, "final-on-a");
        assertThat(scene.sessions.drain(scene.player, NODE_A, epoch).isSuccess())
                .isTrue();
        assertThat(scene.sessions
                        .prepareHandoff(scene.player, NODE_A, epoch, HANDOFF, NODE_B)
                        .isSuccess())
                .isTrue();
        return epoch;
    }
}
