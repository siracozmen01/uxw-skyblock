package com.uxplima.uxmskyblock.core.domain.lifecycle;

/** A moment in a player's life on an island that the operator's lifecycle rules answer. */
public enum LifecycleEvent {
    /** The player left their island of their own accord. */
    LEAVE,
    /** The player was removed from their island by someone who may. */
    KICK,
    /** The player died. */
    DEATH,
    /** The player's island was reset. */
    RESET
}
