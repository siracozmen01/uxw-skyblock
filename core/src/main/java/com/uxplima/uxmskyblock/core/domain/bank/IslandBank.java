package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Immutable domain record representing an island treasury bank account.
 *
 * <p>Balances are canonically tracked as exact integer minor units (e.g. cents/kuruş) to eliminate
 * floating point rounding errors. Optimistic concurrency control is enforced via monotonic versioning.
 *
 * <p>Beside its three columns a bank holds every currency the operator lists in {@code modules/bank.conf}:
 * experience, points, an item, a second economy. Each is kept in whole units under the id the operator gave it.
 *
 * @param islandId unique island identifier
 * @param primaryBalanceMinorUnits exact primary currency balance in minor units
 * @param crystalsBalance premium crystals balance
 * @param expBalance island experience point treasury balance
 * @param version monotonic optimistic concurrency control version (starts at 1)
 * @param updatedAt timestamp of last balance modification
 * @param held the operator's own currencies, by id, in whole units; a currency the island never held is absent
 */
public record IslandBank(
        IslandId islandId,
        long primaryBalanceMinorUnits,
        long crystalsBalance,
        long expBalance,
        long version,
        Instant updatedAt,
        Map<String, Long> held) {

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
        held = Map.copyOf(Objects.requireNonNull(held, "held"));
        held.forEach((currency, balance) -> {
            if (balance < 0) {
                throw new IllegalArgumentException(currency + " cannot be negative: " + balance);
            }
        });
    }

    /** A bank holding nothing beyond its three columns. */
    public IslandBank(
            IslandId islandId,
            long primaryBalanceMinorUnits,
            long crystalsBalance,
            long expBalance,
            long version,
            Instant updatedAt) {
        this(islandId, primaryBalanceMinorUnits, crystalsBalance, expBalance, version, updatedAt, Map.of());
    }

    /** What the bank holds of the operator's currency {@code currency}, zero when it never held any. */
    public long heldOf(String currency) {
        return held.getOrDefault(Objects.requireNonNull(currency, "currency"), 0L);
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
