package com.uxplima.uxmskyblock.core.domain.warp;

/** Thrown when a warp name is empty, too long, or holds a character a warp name may not. */
public class InvalidWarpNameException extends IllegalArgumentException {

    public InvalidWarpNameException(String name) {
        super("Warp name must be 1-32 alphanumeric characters, dashes, or underscores: '" + name + "'");
    }
}
