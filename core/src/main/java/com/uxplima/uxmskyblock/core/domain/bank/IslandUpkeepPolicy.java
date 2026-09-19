package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration policy governing island upkeep fees and bankruptcy escalation.
 *
 * @param enabled whether the automated upkeep debit cycle is active
 * @param interval recurrence cadence between upkeep debit evaluations (e.g. 24h)
 * @param baseFeeMinorUnits baseline fixed maintenance fee in minor currency units (e.g. 50,000 = $500.00)
 * @param perMemberFeeMinorUnits incremental maintenance fee per registered island member in minor units
 * @param graceDuration window of time before an insolvent island transitions from GRACE to LOCKED (e.g. 72h)
 * @param autoRemediateOnDeposit whether depositing into the island bank automatically settles arrears
 */
public record IslandUpkeepPolicy(
        boolean enabled,
        Duration interval,
        long baseFeeMinorUnits,
        long perMemberFeeMinorUnits,
        Duration graceDuration,
        boolean autoRemediateOnDeposit) {

    public IslandUpkeepPolicy {
        Objects.requireNonNull(interval, "interval must not be null");
        Objects.requireNonNull(graceDuration, "graceDuration must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (graceDuration.isNegative() || graceDuration.isZero()) {
            throw new IllegalArgumentException("graceDuration must be positive");
        }
        if (baseFeeMinorUnits < 0) {
            throw new IllegalArgumentException("baseFeeMinorUnits cannot be negative");
        }
        if (perMemberFeeMinorUnits < 0) {
            throw new IllegalArgumentException("perMemberFeeMinorUnits cannot be negative");
        }
    }

    /**
     * Calculates the total upkeep fee for an island based on its active member count.
     */
    public long calculateUpkeepFee(int memberCount) {
        long effectiveMembers = Math.max(0, memberCount);
        return baseFeeMinorUnits + (effectiveMembers * perMemberFeeMinorUnits);
    }

    /**
     * Returns the canonical default upkeep policy where upkeep is disabled by default.
     */
    public static IslandUpkeepPolicy defaultPolicy() {
        return new IslandUpkeepPolicy(false, Duration.ofHours(24), 50_000L, 10_000L, Duration.ofHours(72), true);
    }
}
