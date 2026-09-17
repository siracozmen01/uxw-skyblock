package com.uxplima.uxmskyblock.core.domain.upgrade;

import java.util.Objects;

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

    record PaymentFailed(String reason) implements UpgradePurchaseOutcome {
        public PaymentFailed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record UpgradeNotFound(UpgradeId upgradeId) implements UpgradePurchaseOutcome {
        public UpgradeNotFound {
            Objects.requireNonNull(upgradeId, "upgradeId");
        }
    }
}
