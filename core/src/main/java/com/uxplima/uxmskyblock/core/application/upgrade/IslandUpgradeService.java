package com.uxplima.uxmskyblock.core.application.upgrade;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;

/**
 * Domain service coordinating upgrade progression, tier verification, and financial settlement.
 */
public final class IslandUpgradeService {

    private final IslandUpgradeStoragePort storagePort;
    private final Map<UpgradeId, UpgradeDefinition> definitions;

    public IslandUpgradeService(IslandUpgradeStoragePort storagePort, Map<UpgradeId, UpgradeDefinition> definitions) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort");
        this.definitions = (definitions == null) ? Map.of() : Map.copyOf(definitions);
    }

    public Optional<UpgradeDefinition> getDefinition(UpgradeId upgradeId) {
        return Optional.ofNullable(definitions.get(upgradeId));
    }

    public int getCurrentTier(IslandId islandId, UpgradeId upgradeId) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        return storagePort.getUpgradeTier(islandId, upgradeId);
    }

    public Map<UpgradeId, Integer> getAllUpgrades(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        return storagePort.getUpgrades(islandId);
    }

    /**
     * Attempts to purchase the next tier of the specified upgrade.
     *
     * @param islandId target island ID
     * @param upgradeId target upgrade identifier
     * @param actorUuid player UUID making the purchase
     * @param bankPort island bank application port for settlement
     * @param currentNode cluster node asserting authority
     * @param expectedEpoch authority lease epoch
     * @return purchase outcome
     */
    public UpgradePurchaseOutcome purchaseUpgrade(
            IslandId islandId,
            UpgradeId upgradeId,
            UUID actorUuid,
            IslandBankPort bankPort,
            String currentNode,
            long expectedEpoch) {

        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(bankPort, "bankPort");
        Objects.requireNonNull(currentNode, "currentNode");

        UpgradeDefinition definition = definitions.get(upgradeId);
        if (definition == null) {
            return new UpgradePurchaseOutcome.UpgradeNotFound(upgradeId);
        }

        int currentTier = storagePort.getUpgradeTier(islandId, upgradeId);
        if (currentTier >= definition.maxTier()) {
            return new UpgradePurchaseOutcome.MaxTierReached(upgradeId, currentTier);
        }

        int nextTierNum = currentTier + 1;
        UpgradeTier nextTier = definition.getTier(nextTierNum).orElseThrow();

        if (nextTier.costMinorUnits() > 0) {
            IslandBank bank = bankPort.findBankByIslandId(islandId).orElseGet(() -> bankPort.createBank(islandId));

            UUID operationId = UUID.randomUUID();
            String idempotencyKey = "upg-" + upgradeId.key() + "-" + nextTierNum + "-" + operationId;

            BankTransactionOutcome bankOutcome = bankPort.executeTransaction(
                    islandId,
                    actorUuid,
                    nextTier.currencyId(),
                    2,
                    -nextTier.costMinorUnits(),
                    "Upgrade " + upgradeId.key() + " to tier " + nextTierNum,
                    currentNode,
                    expectedEpoch,
                    bank.version(),
                    operationId,
                    idempotencyKey);

            if (bankOutcome instanceof BankTransactionOutcome.InsufficientFunds rej) {
                return new UpgradePurchaseOutcome.InsufficientFunds(nextTier.costMinorUnits(), rej.currentBalance());
            } else if (!(bankOutcome instanceof BankTransactionOutcome.Success)) {
                return new UpgradePurchaseOutcome.PaymentFailed("Bank transaction failed: " + bankOutcome);
            }
        }

        // Apply new tier
        storagePort.setUpgradeTier(islandId, upgradeId, nextTierNum);
        return new UpgradePurchaseOutcome.Success(upgradeId, nextTierNum, nextTier.costMinorUnits());
    }
}
