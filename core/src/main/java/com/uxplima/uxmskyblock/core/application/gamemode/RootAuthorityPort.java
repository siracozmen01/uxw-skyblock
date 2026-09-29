package com.uxplima.uxmskyblock.core.application.gamemode;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The single writer of a root that is not an island, leased the way an island's is.
 *
 * <p>The algorithm is the island's, not a second one: one row per root locked by the engine's own row
 * lock, an epoch that only a takeover advances, a renewal that only the holder of the live epoch may
 * make, and a takeover that only a lapsed lease allows. A write from a node that lost the root carries
 * the old epoch and is refused.
 */
public interface RootAuthorityPort {

    /** Takes the root for a node when nobody holds it. The first epoch is 1. */
    IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds);

    /** Extends the lease of the node that holds the root under {@code expectedEpoch}, while it still runs. */
    IslandAuthorityOutcome renew(AuthorityRoot root, ServerNodeId node, long expectedEpoch, int leaseSeconds);

    /** Takes a root whose lease has run out, advancing its epoch past {@code expectedEpoch}. */
    IslandAuthorityOutcome takeover(AuthorityRoot root, ServerNodeId newNode, long expectedEpoch, int leaseSeconds);

    /** The root's lease as the table holds it. */
    Optional<RootAuthorityRecord> find(AuthorityRoot root);
}
