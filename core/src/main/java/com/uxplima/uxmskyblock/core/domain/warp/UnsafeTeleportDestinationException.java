package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

public class UnsafeTeleportDestinationException extends RuntimeException {

    private final WarpLocation attemptedLocation;
    private final String errorCode;

    public UnsafeTeleportDestinationException(WarpLocation attemptedLocation, String errorCode) {
        super("Destination at " + attemptedLocation.worldName() + " (" + attemptedLocation.x() + ", "
                + attemptedLocation.y() + ", " + attemptedLocation.z()
                + ") is unsafe and no safe alternative was found within bounding box. Error: " + errorCode);
        this.attemptedLocation = Objects.requireNonNull(attemptedLocation, "attemptedLocation must not be null");
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public WarpLocation attemptedLocation() {
        return attemptedLocation;
    }

    public String errorCode() {
        return errorCode;
    }
}
