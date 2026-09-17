package com.uxplima.uxmskyblock.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable public bank balance for an island.
 */
public record IslandBankBalance(UUID islandId, long balanceMinorUnits) {

    public IslandBankBalance {
        Objects.requireNonNull(islandId, "islandId must not be null");
    }

    public double balance() {
        return balanceMinorUnits / 100.0;
    }
}
