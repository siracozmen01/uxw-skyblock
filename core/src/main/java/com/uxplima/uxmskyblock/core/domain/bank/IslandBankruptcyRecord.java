package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record tracking an island's bankruptcy state, outstanding debt, and grace period.
 *
 * @param islandId target island identity
 * @param status current bankruptcy phase (SOLVENT, GRACE, LOCKED)
 * @param debtMinorUnits exact outstanding arrears in minor currency units (cents/kuruş)
 * @param graceUntil expiration timestamp of the grace period (null when SOLVENT or LOCKED)
 * @param updatedAt timestamp of the last status or balance transition
 */
public record IslandBankruptcyRecord(
        IslandId islandId,
        BankruptcyStatus status,
        long debtMinorUnits,
        @Nullable Instant graceUntil,
        Instant updatedAt) {

    public IslandBankruptcyRecord {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (debtMinorUnits < 0) {
            throw new IllegalArgumentException("debtMinorUnits cannot be negative: " + debtMinorUnits);
        }
    }

    /**
     * Creates an initial solvent record for an island with zero debt.
     */
    public static IslandBankruptcyRecord solvent(IslandId islandId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new IslandBankruptcyRecord(islandId, BankruptcyStatus.SOLVENT, 0L, null, now);
    }

    /**
     * Determines whether the island is currently under active quarantine lockout.
     * An island is locked if its recorded status is LOCKED, or if it is in GRACE and the grace period has expired.
     */
    public boolean isLockoutActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status == BankruptcyStatus.LOCKED) {
            return true;
        }
        if (status == BankruptcyStatus.GRACE) {
            return graceUntil != null && !now.isBefore(graceUntil);
        }
        return false;
    }

    /**
     * Determines whether the island is currently within an active, unexpired grace window.
     */
    public boolean isGraceActive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return status == BankruptcyStatus.GRACE && graceUntil != null && now.isBefore(graceUntil);
    }

    /**
     * Returns the remaining duration of the grace period, or empty if not in active grace.
     */
    public Optional<Duration> remainingGrace(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!isGraceActive(now) || graceUntil == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, graceUntil));
    }

    /**
     * Transitions this record into the GRACE phase with an updated debt balance and grace deadline.
     */
    public IslandBankruptcyRecord toGrace(long totalDebtMinorUnits, Instant graceDeadline, Instant now) {
        Objects.requireNonNull(graceDeadline, "graceDeadline must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new IslandBankruptcyRecord(islandId, BankruptcyStatus.GRACE, totalDebtMinorUnits, graceDeadline, now);
    }

    /**
     * Transitions this record into the LOCKED phase upon grace expiration.
     */
    public IslandBankruptcyRecord toLocked(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new IslandBankruptcyRecord(islandId, BankruptcyStatus.LOCKED, debtMinorUnits, null, now);
    }

    /**
     * Adds additional debt to an existing grace or locked record without altering the grace period.
     */
    public IslandBankruptcyRecord addDebt(long additionalDebtMinorUnits, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (additionalDebtMinorUnits < 0) {
            throw new IllegalArgumentException("additionalDebtMinorUnits cannot be negative");
        }
        return new IslandBankruptcyRecord(islandId, status, debtMinorUnits + additionalDebtMinorUnits, graceUntil, now);
    }

    /**
     * Atomically clears all debt and returns this island to SOLVENT status.
     */
    public IslandBankruptcyRecord toSolvent(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new IslandBankruptcyRecord(islandId, BankruptcyStatus.SOLVENT, 0L, null, now);
    }
}
