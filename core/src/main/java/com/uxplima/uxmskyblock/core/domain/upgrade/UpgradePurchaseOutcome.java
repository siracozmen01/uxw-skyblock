package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Sealed algebraic data type representing the result of attempting to purchase an island upgrade.
 */
public sealed interface UpgradePurchaseOutcome {

    record Success(UpgradeId upgradeId, int newTier, long costPaid) implements UpgradePurchaseOutcome {
        public Success {
            Objects.requireNonNull(upgradeId, "upgradeId");
        }
    }

    record MaxTierReached(UpgradeId upgradeId, int currentTier) implements UpgradePurchaseOutcome {
        public MaxTierReached {
            Objects.requireNonNull(upgradeId, "upgradeId");
        }
    }

    record InsufficientFunds(long requiredAmount, long availableAmount) implements UpgradePurchaseOutcome {}

    /**
     * The upgrade was not bought and nothing was charged.
     *
     * @param kind what kind of refusal it is, which is what the player is told
     * @param reason what happened, for the log and never for the player
     * @param bank what the bank answered, when it was the bank that refused
     */
    record PaymentFailed(Kind kind, String reason, @Nullable BankTransactionOutcome bank)
            implements UpgradePurchaseOutcome {
        public PaymentFailed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
        }

        public PaymentFailed(Kind kind, String reason) {
            this(kind, reason, null);
        }

        /** Why an upgrade was not bought, in the terms a player is told. */
        public enum Kind {
            /** This server cannot sell upgrades at all. */
            UNAVAILABLE,
            /** Another server looks after this island right now. */
            ELSEWHERE,
            /** The island bank refused the payment. */
            BANK_REFUSED,
            /** Another purchase moved the upgrade first, and the payment was given back. */
            RACED
        }
    }

    record UpgradeNotFound(UpgradeId upgradeId) implements UpgradePurchaseOutcome {
        public UpgradeNotFound {
            Objects.requireNonNull(upgradeId, "upgradeId");
        }
    }
}
