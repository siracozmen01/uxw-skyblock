package com.uxplima.uxmskyblock.bukkit.arrival;

/** What brought a player to where they arrived. */
public enum ArrivalCause {
    /** A command, a plugin or the server moved them. */
    TELEPORT,
    /** Their own ender pearl landed. */
    PEARL,
    /** They came back from death. */
    RESPAWN,
    /** They logged in. */
    JOIN,
    /** Something they ride carried them. */
    VEHICLE
}
