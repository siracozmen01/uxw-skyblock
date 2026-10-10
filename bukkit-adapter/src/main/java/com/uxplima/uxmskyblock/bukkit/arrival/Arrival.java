package com.uxplima.uxmskyblock.bukkit.arrival;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import org.jspecify.annotations.Nullable;

/**
 * A player reaching {@code to}.
 *
 * @param from where they were last seen, or nothing when they have just logged in
 * @param before whether the move is still to come, so refusing it stops it, or has already happened, so
 *     refusing it sends them back
 */
public record Arrival(Player player, @Nullable Location from, Location to, ArrivalCause cause, boolean before) {

    public Arrival {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
    }
}
