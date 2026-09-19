package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Instant;

/**
 * Sealed hierarchy of outcomes from executing an island upkeep cycle.
 */
public sealed interface BankruptcyCycleResult {

    record Paid(long amountDebited, long remainingBalance) implements BankruptcyCycleResult {}

    record GraceEntered(long shortfall, Instant graceUntil, long totalDebt) implements BankruptcyCycleResult {}

    record GraceExtended(long shortfall, Instant graceUntil, long totalDebt) implements BankruptcyCycleResult {}

    record LockoutApplied(long shortfall, long totalDebt) implements BankruptcyCycleResult {}

    record SkippedDisabled() implements BankruptcyCycleResult {}
}
