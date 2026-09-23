package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Instant;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Sealed hierarchy of outcomes from executing an island upkeep cycle.
 */
public sealed interface BankruptcyCycleResult {

    record Paid(long amountDebited, long remainingBalance) implements BankruptcyCycleResult {}

    record GraceEntered(long shortfall, Instant graceUntil, long totalDebt) implements BankruptcyCycleResult {}

    record GraceExtended(long shortfall, Instant graceUntil, long totalDebt) implements BankruptcyCycleResult {}

    record LockoutApplied(long shortfall, long totalDebt) implements BankruptcyCycleResult {}

    record SkippedDisabled() implements BankruptcyCycleResult {}

    /** The island was already charged for this period, paid or owed, so nothing was taken. */
    record AlreadyCharged(long period) implements BankruptcyCycleResult {}

    /** Another node holds the island and charges it, so this node took nothing. */
    record HeldElsewhere(ServerNodeId holder) implements BankruptcyCycleResult {}

    /**
     * The charge could not be settled this time: the bank moved under it or the island's authority
     * refused it. Nothing was taken and nothing was owed, and the next cycle tries again.
     */
    record Deferred(String outcome) implements BankruptcyCycleResult {}
}
