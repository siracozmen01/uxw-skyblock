package com.uxplima.uxmskyblock.core.domain.bank;

/**
 * Sealed hierarchy of outcomes from attempting to settle island arrears and remediate bankruptcy.
 */
public sealed interface BankruptcyRemediationResult {

    record Settled(long amountPaid, long remainingBalance) implements BankruptcyRemediationResult {}

    record InsufficientFunds(long debtAmount, long currentBalance) implements BankruptcyRemediationResult {}

    record NotInArrears() implements BankruptcyRemediationResult {}
}
