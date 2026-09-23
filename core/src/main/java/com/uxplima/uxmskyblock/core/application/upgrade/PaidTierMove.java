package com.uxplima.uxmskyblock.core.application.upgrade;

import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;

/** What became of a {@link TierPurchase}. */
public sealed interface PaidTierMove {

    /** The bank was charged and the tier moved, in one transaction. */
    record Moved(BankTransactionOutcome.Success charge) implements PaidTierMove {}

    /** The bank refused the charge, so nothing was taken and the tier did not move. */
    record Refused(BankTransactionOutcome outcome) implements PaidTierMove {}

    /** Another purchase moved the tier first. The charge was rolled back with the move. */
    record Raced() implements PaidTierMove {}
}
