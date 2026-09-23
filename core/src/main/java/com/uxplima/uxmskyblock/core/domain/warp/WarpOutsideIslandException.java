package com.uxplima.uxmskyblock.core.domain.warp;

/** Thrown when a warp would be set, or moved, to a spot outside the island it belongs to. */
public class WarpOutsideIslandException extends IllegalArgumentException {

    public WarpOutsideIslandException(String message) {
        super(message);
    }
}
