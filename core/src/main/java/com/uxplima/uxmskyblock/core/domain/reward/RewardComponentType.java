package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Protocol classification for delivery of a reward component.
 */
public enum RewardComponentType {
    /**
     * Minecraft inventory items, bundles, or equipment delivered via InventoryMutationJournal.
     */
    ITEM,

    /**
     * Relational SQL-owned economic currencies or bank balances via canonical OCC and processed_operations.
     */
    SQL_CURRENCY,

    /**
     * External Vault economy side-effects delivered via saga/idempotency bridge.
     */
    EXTERNAL_VAULT,

    /**
     * Cosmetic unlocks, titles, trails, or pets.
     */
    COSMETIC,

    /**
     * Permissions or permission group grants.
     */
    PERMISSION
}
