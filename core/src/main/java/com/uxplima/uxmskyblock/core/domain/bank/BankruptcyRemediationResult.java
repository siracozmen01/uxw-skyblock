package com.uxplima.uxmskyblock.core.domain.bank;

/**
 * Sealed hierarchy of outcomes from attempting to settle island arrears and remediate bankruptcy.
 */
public sealed interface BankruptcyRemediationResult {

    record Settled(long amountPaid, long remainingBalance) implements BankruptcyRemediationResult {}

    record InsufficientFunds(long debtAmount, long currentBalance) implements BankruptcyRemediationResult {}

    record NotInArrears() implements BankruptcyRemediationResult {}

    /**
     * The island had the money and the bank refused the write anyway.
     *
     * <p>This used to be answered as insufficient funds, which is what a player reads when a second
     * settlement lands on a debt the first one has already cleared: they are told they cannot afford
     * something they have just paid for.
     */
    record PaymentRefused(long debtAmount, String reason) implements BankruptcyRemediationResult {}
}
