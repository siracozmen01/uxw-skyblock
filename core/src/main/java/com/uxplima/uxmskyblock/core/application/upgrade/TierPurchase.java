package com.uxplima.uxmskyblock.core.application.upgrade;

import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * One paid tier move: the bank charge and the tier it buys, which happen together or not at all.
 *
 * @param fromTier the tier read before the purchase, which the move requires to be there still
 * @param toTier the tier the charge buys
 */
public record TierPurchase(
        IslandId islandId,
        UpgradeId upgradeId,
        int fromTier,
        int toTier,
        UUID actorUuid,
        String currencyId,
        long costMinorUnits,
        String reason,
        String currentNode,
        long expectedEpoch,
        long expectedBankVersion,
        UUID operationId,
        String idempotencyKey) {

    public TierPurchase {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (costMinorUnits <= 0) {
            throw new IllegalArgumentException("a paid tier move costs something: " + costMinorUnits);
        }
    }
}
