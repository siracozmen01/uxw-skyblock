package com.uxplima.uxmskyblock.core.domain.shop;

import java.time.Instant;
import java.util.Objects;

/**
 * Event published when the unit price of a shop commodity changes.
 *
 * @param itemKey namespaced item identifier
 * @param oldPrice previous price in minor units
 * @param newPrice updated price in minor units
 * @param timestamp timestamp of the update
 */
public record PriceUpdateEvent(String itemKey, long oldPrice, long newPrice, Instant timestamp) {

    public PriceUpdateEvent {
        Objects.requireNonNull(itemKey, "itemKey must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public long priceDelta() {
        return newPrice - oldPrice;
    }
}
