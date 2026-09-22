package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authority lease is pushed forward while the node is up.
 *
 * <p>It was taken once, when the island was made, and nothing renewed it. Every write that needs
 * authority is refused once it runs out, so an island stopped being able to use its own bank one
 * lease after it was created and never started again.
 */
class TheAuthorityHeartbeatTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

    @Test
    @DisplayName("A beat asks the store to sweep this node's world for this node's lease")
    void aBeatSweepsThisNodesWorld() {
        RecordingAuthorityPort port = new RecordingAuthorityPort();
        IslandAuthorityService service = new IslandAuthorityService(port, NODE, "skyblock", Duration.ofMinutes(10));

        IslandAuthoritySweep swept = service.heartbeat();

        assertThat(port.sweeps).containsExactly(new Sweep(NODE, "skyblock", 600));
        assertThat(swept.renewed()).isEqualTo(4);
        assertThat(swept.takenOver()).isEqualTo(2);
        assertThat(swept.acquired()).isEqualTo(1);
        assertThat(swept.total()).isEqualTo(7);
    }

    @Test
    @DisplayName("The lease the operator set is the lease that goes to the store")
    void theOperatorSetsTheLease() {
        RecordingAuthorityPort port = new RecordingAuthorityPort();
        new IslandAuthorityService(port, NODE, "skyblock", Duration.ofSeconds(90)).heartbeat();

        assertThat(port.sweeps).containsExactly(new Sweep(NODE, "skyblock", 90));
    }

    @Test
    @DisplayName("A beat that throws is survived, because the next one is what puts it right")
    void aFailingBeatIsSurvived() {
        RecordingAuthorityPort port = new RecordingAuthorityPort();
        port.throwing = true;
        IslandAuthorityService service = new IslandAuthorityService(port, NODE, "skyblock", Duration.ofMinutes(10));

        assertThat(service.heartbeat())
                .describedAs("nothing swept, and no exception out")
                .isEqualTo(IslandAuthoritySweep.NOTHING);

        port.throwing = false;
        assertThat(service.heartbeat().total())
                .describedAs("the next beat works")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("A lease of nothing is refused rather than accepted")
    void aLeaseOfNothingIsRefused() {
        assertThatThrownBy(
                        () -> new IslandAuthorityService(new RecordingAuthorityPort(), NODE, "skyblock", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A sweep cannot count backwards")
    void aSweepCannotCountBackwards() {
        assertThatThrownBy(() -> new IslandAuthoritySweep(1, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private record Sweep(ServerNodeId nodeId, String worldName, int leaseSeconds) {}

    /** Writes down what it was asked to sweep, and can be made to throw. */
    private static final class RecordingAuthorityPort implements IslandAuthorityPort {

        final List<Sweep> sweeps = new ArrayList<>();
        boolean throwing;

        @Override
        public IslandAuthoritySweep sweepAuthority(ServerNodeId nodeId, String worldName, int leaseSeconds) {
            if (throwing) {
                throw new IllegalStateException("the database is gone");
            }
            sweeps.add(new Sweep(nodeId, worldName, leaseSeconds));
            return new IslandAuthoritySweep(4, 2, 1);
        }

        @Override
        public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IslandAuthorityOutcome renewAuthority(
                IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IslandAuthorityOutcome takeoverAuthority(
                IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IslandAuthorityRecord> findAuthority(IslandId islandId) {
            return Optional.empty();
        }
    }
}
