package com.uxplima.uxmskyblock.core.application.shop;

import com.uxplima.uxmskyblock.core.domain.shop.PriceUpdateEvent;

/**
 * Listener notified when the price of a tradeable commodity changes.
 */
@FunctionalInterface
public interface DynamicPriceChangeListener {

    /**
     * Invoked when a commodity unit price changes.
     *
     * @param event details of the price change
     */
    void onPriceChanged(PriceUpdateEvent event);
}
