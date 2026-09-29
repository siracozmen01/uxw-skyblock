package com.uxplima.uxmskyblock.core.domain.gamemode;

/**
 * Supported game mode classifications.
 */
public enum GameModeType {
    SKYBLOCK,
    SURVIVAL,
    ONEBLOCK,
    CHUNKBLOCK,
    ACID_ISLAND,
    CAVEBLOCK,
    SKYGRID,
    BOXED,
    POSEIDON;

    /** Whether the mode is played under water, so that water is room to arrive in rather than a danger. */
    public boolean playedUnderwater() {
        return this == POSEIDON;
    }
}
