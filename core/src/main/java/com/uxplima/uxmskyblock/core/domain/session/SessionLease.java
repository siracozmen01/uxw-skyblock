package com.uxplima.uxmskyblock.core.domain.session;

import java.time.Duration;

/**
 * How long a session lease lasts, which the database grants and every node holding one must respect.
 *
 * <p>The database writes these into {@code player_sessions}, and the node that holds a session
 * counts down the same span on its own monotonic clock, from the moment it asked, so it stops before
 * the database would give the session to someone else.
 */
public final class SessionLease {

    /** How long an active session lasts from its last renewal. */
    public static final Duration ACTIVE = Duration.ofSeconds(15);

    /** How long a session readied for a handoff waits for its destination. */
    public static final Duration HANDOFF = Duration.ofSeconds(30);

    /**
     * How long before the database lease runs out a node stops acting on it: the time a renewal's
     * answer may still be on its way, and a clock that is a little off.
     */
    public static final Duration SAFETY_MARGIN = Duration.ofSeconds(3);

    private SessionLease() {}

    /** The span a node may act on a lease it asked for, counted from the moment it asked. */
    public static Duration locallyHeld() {
        return ACTIVE.minus(SAFETY_MARGIN);
    }
}
