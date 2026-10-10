package com.uxplima.uxmskyblock.bukkit.arrival;

/** Something that follows where players arrive. It runs on the player's own thread. */
@FunctionalInterface
public interface ArrivalObserver {

    void arrived(Arrival arrival);
}
