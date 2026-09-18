package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

public class WarpLockedException extends RuntimeException {

    private final WarpName warpName;

    public WarpLockedException(WarpName warpName) {
        super("Warp '" + warpName + "' is locked to visitors");
        this.warpName = Objects.requireNonNull(warpName, "warpName must not be null");
    }

    public WarpName warpName() {
        return warpName;
    }
}
