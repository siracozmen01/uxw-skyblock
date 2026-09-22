package com.uxplima.uxmskyblock.core.domain.island;

/**
 * What one pass of the authority heartbeat did.
 *
 * @param renewed leases this node already held and pushed forward
 * @param takenOver leases that had run out and this node picked up, each with a new epoch
 * @param acquired islands in this world that had no authority row at all
 */
public record IslandAuthoritySweep(int renewed, int takenOver, int acquired) {

    public static final IslandAuthoritySweep NOTHING = new IslandAuthoritySweep(0, 0, 0);

    public IslandAuthoritySweep {
        if (renewed < 0 || takenOver < 0 || acquired < 0) {
            throw new IllegalArgumentException(
                    "a sweep cannot count backwards: " + renewed + ", " + takenOver + ", " + acquired);
        }
    }

    /** Whether the pass changed anything at all. */
    public boolean touchedAnything() {
        return renewed + takenOver + acquired > 0;
    }

    public int total() {
        return renewed + takenOver + acquired;
    }
}
