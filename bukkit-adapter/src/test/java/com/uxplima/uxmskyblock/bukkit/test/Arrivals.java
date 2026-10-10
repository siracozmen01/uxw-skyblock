package com.uxplima.uxmskyblock.bukkit.test;

import java.time.Clock;

import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalGate;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalObserver;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch;

/** Puts a teleport through a real arrival watch, the way the server hands it to a rule. */
public final class Arrivals {

    private Arrivals() {}

    /** The teleport as Paper announces it, before the move, put to {@code gate}. */
    public static void teleport(ArrivalGate gate, PlayerTeleportEvent event) {
        ArrivalWatch watch = watch();
        watch.admit(gate);
        watch.onTeleport(event);
    }

    /** The teleport once no rule refused it, told to {@code observer}. */
    public static void landed(ArrivalObserver observer, PlayerTeleportEvent event) {
        ArrivalWatch watch = watch();
        watch.observe(observer);
        watch.afterTeleport(event);
    }

    private static ArrivalWatch watch() {
        return new ArrivalWatch(new InlineSchedulerPort(), () -> new Location(null, 0, 0, 0), Clock.systemUTC());
    }
}
