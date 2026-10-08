package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The single writer of a vessel's cargo hold: the node that holds its lease.
 *
 * <p>A hold is written only under a lease this node holds. It is taken when nobody holds it, renewed when
 * this node does, and taken over once another node's lease ran out. While another node holds a live lease,
 * the hold is that node's and this one does not write it.
 */
public final class VesselLease {

    /** How long a lease runs before it has to be renewed. */
    public static final int LEASE_SECONDS = 30;

    private final RootAuthorityPort authority;
    private final ServerNodeId node;
    private final Clock clock;

    public VesselLease(RootAuthorityPort authority, ServerNodeId node, Clock clock) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The root a vessel's lease is kept under. */
    public static AuthorityRoot rootOf(IslandId vessel) {
        return AuthorityRoot.modeOwned(
                CargoJournalPort.LEASE_PROVIDER, vessel.value().toString());
    }

    /**
     * Takes or renews the vessel's lease for this node and gives the epoch it holds it under, or nothing
     * while another node holds it. Off the main thread: it writes a row.
     */
    public OptionalLong hold(IslandId vessel) {
        Objects.requireNonNull(vessel, "vessel");
        AuthorityRoot root = rootOf(vessel);
        Optional<RootAuthorityRecord> found = authority.find(root);
        IslandAuthorityOutcome outcome;
        if (found.isEmpty()) {
            outcome = authority.acquire(root, node, LEASE_SECONDS);
        } else if (found.get().authoritativeNode().equals(node) && found.get().isLive(clock.instant())) {
            outcome = authority.renew(root, node, found.get().authorityEpoch(), LEASE_SECONDS);
        } else if (!found.get().isLive(clock.instant())) {
            outcome = authority.takeover(root, node, found.get().authorityEpoch(), LEASE_SECONDS);
        } else {
            return OptionalLong.empty();
        }
        return outcome instanceof IslandAuthorityOutcome.Success success
                ? OptionalLong.of(success.epoch())
                : OptionalLong.empty();
    }
}
