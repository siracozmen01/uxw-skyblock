package com.uxplima.uxmskyblock.core.application.island;

import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Keeps this node's authority over its islands alive.
 *
 * <p>An island's authority lease was taken once, when the island was made, and nothing ever renewed
 * it. Every write that needs authority is refused once the lease has run out: a bank deposit, a
 * withdrawal, an upgrade purchase. So an island stopped being able to use its own bank one lease
 * after it was created, and nothing ever put it right. A server that had been down longer than the
 * lease came back with no authority over anything and no way to get it.
 *
 * <p>The heartbeat is what was missing. It pushes this node's leases forward, picks up the ones in
 * this world that ran out, and gives a row to an island here that has none.
 */
public final class IslandAuthorityService {

    private static final Logger LOGGER = Logger.getLogger(IslandAuthorityService.class.getName());

    /** How long a lease runs, when the operator names no number. */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(10);

    /** How often the lease is pushed forward, when the operator names no number. */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofMinutes(2);

    private final IslandAuthorityPort authorityPort;
    private final ServerNodeId nodeId;
    private final String worldName;
    private final Duration lease;

    public IslandAuthorityService(
            IslandAuthorityPort authorityPort, ServerNodeId nodeId, String worldName, Duration lease) {
        this.authorityPort = Objects.requireNonNull(authorityPort, "authorityPort must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(lease, "lease must not be null");
        if (lease.toSeconds() < 1) {
            throw new IllegalArgumentException("the lease must be at least a second: " + lease);
        }
        this.lease = lease;
    }

    /** How long a lease this service takes runs for. */
    public Duration lease() {
        return lease;
    }

    /** The lease in the seconds the port speaks in. */
    public int leaseSeconds() {
        long seconds = lease.toSeconds();
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    /**
     * One pass of the heartbeat.
     *
     * <p>A pass that fails is logged and the next one tries again. Taking the repeating task down
     * over one unreachable database would turn a blip into an island that can never bank again.
     */
    public IslandAuthoritySweep heartbeat() {
        try {
            IslandAuthoritySweep swept = authorityPort.sweepAuthority(nodeId, worldName, leaseSeconds());
            if (swept.takenOver() > 0 || swept.acquired() > 0) {
                LOGGER.info(() -> "Island authority: renewed " + swept.renewed() + ", took over " + swept.takenOver()
                        + ", claimed " + swept.acquired() + " in world " + worldName + ".");
            } else if (swept.renewed() > 0) {
                LOGGER.fine(
                        () -> "Island authority: renewed " + swept.renewed() + " leases in world " + worldName + ".");
            }
            return swept;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "The island authority heartbeat failed. The next one tries again.");
            return IslandAuthoritySweep.NOTHING;
        }
    }
}
