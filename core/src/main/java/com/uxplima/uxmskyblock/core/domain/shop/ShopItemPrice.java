package com.uxplima.uxmskyblock.core.domain.shop;

import java.time.Instant;
import java.util.Objects;

/**
 * Snapshot of the active economic state, volume statistics, and price of a tradeable shop commodity.
 *
 * @param itemKey namespaced item identifier (e.g. "minecraft:diamond")
 * @param currentPrice active unit price in minor currency units
 * @param supply total units sold into the market
 * @param demand total units purchased from the market
 * @param curve active pricing curve parameters
 * @param lastUpdated timestamp of last price recalculation
 */
public record ShopItemPrice(
        String itemKey, long currentPrice, long supply, long demand, PricingCurve curve, Instant lastUpdated) {

    public ShopItemPrice {
        Objects.requireNonNull(itemKey, "itemKey must not be null");
        Objects.requireNonNull(curve, "curve must not be null");
        Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");
    }
}
