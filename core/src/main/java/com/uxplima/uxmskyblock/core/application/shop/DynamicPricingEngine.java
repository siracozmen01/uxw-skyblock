package com.uxplima.uxmskyblock.core.application.shop;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.uxplima.uxmskyblock.core.domain.shop.PriceUpdateEvent;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;

/**
 * Enterprise dynamic economy engine tracking market commodity supply/demand volumes,
 * computing bounded pricing curves, and damping speculation swings.
 */
public final class DynamicPricingEngine {

    public static final double DEFAULT_DAMPING_FACTOR = 0.85;

    private final double dampingFactor;
    private final ConcurrentHashMap<String, ShopItemPrice> prices = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DynamicPriceChangeListener> listeners = new CopyOnWriteArrayList<>();

    public DynamicPricingEngine() {
        this(DEFAULT_DAMPING_FACTOR);
    }

    public DynamicPricingEngine(double dampingFactor) {
        if (dampingFactor <= 0.0 || dampingFactor > 1.0) {
            throw new IllegalArgumentException("dampingFactor must be in range (0.0, 1.0]: " + dampingFactor);
        }
        this.dampingFactor = dampingFactor;
    }

    public double dampingFactor() {
        return dampingFactor;
    }

    public void addListener(DynamicPriceChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
    }

    public void removeListener(DynamicPriceChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.remove(listener);
    }

    /**
     * Registers a tradeable commodity with default zero baseline volumes.
     *
     * @param itemKey namespaced item identifier
     * @param curve pricing curve parameters
     */
    public void registerItem(String itemKey, PricingCurve curve) {
        registerItem(itemKey, curve, 0L, 0L);
    }

    /**
     * Registers a tradeable commodity with initial supply and demand volumes.
     *
     * @param itemKey namespaced item identifier
     * @param curve pricing curve parameters
     * @param initialSupply starting supply volume
     * @param initialDemand starting demand volume
     */
    public void registerItem(String itemKey, PricingCurve curve, long initialSupply, long initialDemand) {
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(curve, "curve");
        if (initialSupply < 0) {
            throw new IllegalArgumentException("initialSupply must not be negative: " + initialSupply);
        }
        if (initialDemand < 0) {
            throw new IllegalArgumentException("initialDemand must not be negative: " + initialDemand);
        }

        long target = curve.calculateTargetPrice(initialSupply, initialDemand);
        ShopItemPrice initial = new ShopItemPrice(itemKey, target, initialSupply, initialDemand, curve, Instant.now());
        prices.put(itemKey, initial);
    }

    public boolean hasItem(String itemKey) {
        Objects.requireNonNull(itemKey, "itemKey");
        return prices.containsKey(itemKey);
    }

    public Optional<ShopItemPrice> getPrice(String itemKey) {
        Objects.requireNonNull(itemKey, "itemKey");
        return Optional.ofNullable(prices.get(itemKey));
    }

    public OptionalLong getUnitPrice(String itemKey) {
        Objects.requireNonNull(itemKey, "itemKey");
        ShopItemPrice entry = prices.get(itemKey);
        return entry != null ? OptionalLong.of(entry.currentPrice()) : OptionalLong.empty();
    }

    public Map<String, ShopItemPrice> getAllPrices() {
        return Collections.unmodifiableMap(prices);
    }

    /**
     * Records a purchase transaction (increasing demand and potentially raising unit price).
     *
     * @param itemKey namespaced item identifier
     * @param quantity number of units purchased
     */
    public void recordPurchase(String itemKey, long quantity) {
        Objects.requireNonNull(itemKey, "itemKey");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }

        PriceUpdateEvent[] eventToFire = new PriceUpdateEvent[1];

        prices.compute(itemKey, (k, current) -> {
            if (current == null) {
                return null;
            }
            long newDemand = current.demand() + quantity;
            long rawTarget = current.curve().calculateTargetPrice(current.supply(), newDemand);
            long newPrice = PricingCurve.applyDamping(current.currentPrice(), rawTarget, dampingFactor);

            if (newPrice != current.currentPrice()) {
                eventToFire[0] = new PriceUpdateEvent(k, current.currentPrice(), newPrice, Instant.now());
            }

            return new ShopItemPrice(k, newPrice, current.supply(), newDemand, current.curve(), Instant.now());
        });

        if (eventToFire[0] != null) {
            notifyListeners(eventToFire[0]);
        }
    }

    /**
     * Records a sale transaction (increasing supply and potentially decreasing unit price).
     *
     * @param itemKey namespaced item identifier
     * @param quantity number of units sold
     */
    public void recordSale(String itemKey, long quantity) {
        Objects.requireNonNull(itemKey, "itemKey");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }

        PriceUpdateEvent[] eventToFire = new PriceUpdateEvent[1];

        prices.compute(itemKey, (k, current) -> {
            if (current == null) {
                return null;
            }
            long newSupply = current.supply() + quantity;
            long rawTarget = current.curve().calculateTargetPrice(newSupply, current.demand());
            long newPrice = PricingCurve.applyDamping(current.currentPrice(), rawTarget, dampingFactor);

            if (newPrice != current.currentPrice()) {
                eventToFire[0] = new PriceUpdateEvent(k, current.currentPrice(), newPrice, Instant.now());
            }

            return new ShopItemPrice(k, newPrice, newSupply, current.demand(), current.curve(), Instant.now());
        });

        if (eventToFire[0] != null) {
            notifyListeners(eventToFire[0]);
        }
    }

    /**
     * Overrides the unit price directly (e.g. from an external economy integration sync).
     *
     * @param itemKey namespaced item identifier
     * @param newPrice new price in minor currency units
     */
    public void setPriceDirect(String itemKey, long newPrice) {
        Objects.requireNonNull(itemKey, "itemKey");
        if (newPrice <= 0) {
            throw new IllegalArgumentException("newPrice must be positive: " + newPrice);
        }

        PriceUpdateEvent[] eventToFire = new PriceUpdateEvent[1];

        prices.compute(itemKey, (k, current) -> {
            if (current == null) {
                return null;
            }
            long oldPrice = current.currentPrice();
            if (oldPrice != newPrice) {
                eventToFire[0] = new PriceUpdateEvent(k, oldPrice, newPrice, Instant.now());
            }
            return new ShopItemPrice(k, newPrice, current.supply(), current.demand(), current.curve(), Instant.now());
        });

        if (eventToFire[0] != null) {
            notifyListeners(eventToFire[0]);
        }
    }

    private void notifyListeners(PriceUpdateEvent event) {
        for (DynamicPriceChangeListener listener : listeners) {
            try {
                listener.onPriceChanged(event);
            } catch (Throwable ignored) {
                // Protect pricing engine from external listener exceptions
            }
        }
    }
}
