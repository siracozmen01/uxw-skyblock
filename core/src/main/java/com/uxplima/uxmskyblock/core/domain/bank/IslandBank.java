package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Immutable domain record representing an island treasury bank account.
 *
 * <p>Balances are canonically tracked as exact integer minor units (e.g. cents/kuruş) to eliminate
 * floating point rounding errors. Optimistic concurrency control is enforced via monotonic versioning.
 *
 * @param islandId unique island identifier
 * @param primaryBalanceMinorUnits exact primary currency balance in minor units
 * @param crystalsBalance premium crystals balance
 * @param expBalance island experience point treasury balance
 * @param version monotonic optimistic concurrency control version (starts at 1)
 * @param updatedAt timestamp of last balance modification
 */
public record IslandBank(
        IslandId islandId,
        long primaryBalanceMinorUnits,
        long crystalsBalance,
        long expBalance,
        long version,
        Instant updatedAt) {

    public IslandBank {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (primaryBalanceMinorUnits < 0) {
            throw new IllegalArgumentException(
                    "primaryBalanceMinorUnits cannot be negative: " + primaryBalanceMinorUnits);
        }
        if (crystalsBalance < 0) {
            throw new IllegalArgumentException("crystalsBalance cannot be negative: " + crystalsBalance);
        }
        if (expBalance < 0) {
            throw new IllegalArgumentException("expBalance cannot be negative: " + expBalance);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1: " + version);
        }
    }

    /**
     * Creates an initial zero-balance bank account at version 1.
     *
     * @param islandId target island ID
     * @return initialized island bank account
     */
    public static IslandBank initial(IslandId islandId) {
        return new IslandBank(islandId, 0L, 0L, 0L, 1L, Instant.now());
    }
}
