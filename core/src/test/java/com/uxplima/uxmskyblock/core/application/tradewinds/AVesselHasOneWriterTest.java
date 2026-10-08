package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A vessel's hold has one writer: the node that holds its lease, while the lease runs. */
class AVesselHasOneWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final ServerNodeId A = ServerNodeId.of("node-a");
    private static final ServerNodeId B = ServerNodeId.of("node-b");

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final Leases leases = new Leases();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("A free lease is taken, the holder renews it, and another node is refused while it runs")
    void oneWriterAtATime() {
        assertThat(new VesselLease(leases, A, clock).hold(vessel)).hasValue(1);
        assertThat(new VesselLease(leases, A, clock).hold(vessel)).hasValue(1);
        assertThat(new VesselLease(leases, B, clock).hold(vessel)).isEmpty();
        assertThat(leases.calls).containsExactly("acquire node-a", "renew node-a 1");
        assertThat(VesselLease.rootOf(vessel))
                .isEqualTo(AuthorityRoot.modeOwned(
                        CargoJournalPort.LEASE_PROVIDER, vessel.value().toString()));
    }

    @Test
    @DisplayName("A lease that ran out is taken over under the next epoch")
    void aLapsedLeaseIsTakenOver() {
        leases.records.put(
                VesselLease.rootOf(vessel),
                new RootAuthorityRecord(VesselLease.rootOf(vessel), A, 3, NOW.minusSeconds(1)));

        assertThat(new VesselLease(leases, B, clock).hold(vessel)).hasValue(4);
        assertThat(leases.calls).containsExactly("takeover node-b 3");
    }

    /** Leases in memory, recording each call. */
    private static final class Leases implements RootAuthorityPort {
        private final Map<AuthorityRoot, RootAuthorityRecord> records = new HashMap<>();
        private final List<String> calls = new ArrayList<>();

        @Override
        public IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds) {
            calls.add("acquire " + node.value());
            records.put(root, new RootAuthorityRecord(root, node, 1, NOW.plusSeconds(leaseSeconds)));
            return IslandAuthorityOutcome.success(1);
        }

        @Override
        public IslandAuthorityOutcome renew(
                AuthorityRoot root, ServerNodeId node, long expectedEpoch, int leaseSeconds) {
            calls.add("renew " + node.value() + " " + expectedEpoch);
            records.put(root, new RootAuthorityRecord(root, node, expectedEpoch, NOW.plusSeconds(leaseSeconds)));
            return IslandAuthorityOutcome.success(expectedEpoch);
        }

        @Override
        public IslandAuthorityOutcome takeover(
                AuthorityRoot root, ServerNodeId newNode, long expectedEpoch, int leaseSeconds) {
            calls.add("takeover " + newNode.value() + " " + expectedEpoch);
            records.put(root, new RootAuthorityRecord(root, newNode, expectedEpoch + 1, NOW.plusSeconds(leaseSeconds)));
            return IslandAuthorityOutcome.success(expectedEpoch + 1);
        }

        @Override
        public Optional<RootAuthorityRecord> find(AuthorityRoot root) {
            return Optional.ofNullable(records.get(root));
        }
    }
}
