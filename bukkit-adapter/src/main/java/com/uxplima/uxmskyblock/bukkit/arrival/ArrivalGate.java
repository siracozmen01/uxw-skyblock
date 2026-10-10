package com.uxplima.uxmskyblock.bukkit.arrival;

/** A rule that may refuse an arrival. */
@FunctionalInterface
public interface ArrivalGate {

    /**
     * Whether this rule refuses the arrival. A rule that refuses tells the player why; the watch stops
     * the move or undoes it.
     */
    boolean refuses(Arrival arrival);
}
