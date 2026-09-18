package com.uxplima.uxmskyblock.core.application.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmskyblock.core.domain.shop.PriceUpdateEvent;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DynamicPricingEngineTest {

    private DynamicPricingEngine engine;
    private PricingCurve diamondCurve;

    @BeforeEach
    void setUp() {
        // base: 100, floor: 20, ceiling: 500, elasticity: 0.5, stockBaseline: 100
        diamondCurve = new PricingCurve(100L, 20L, 500L, 0.5, 100L);
        engine = new DynamicPricingEngine(0.85);
    }

    @Test
    @DisplayName("Constructing with invalid damping factor throws IllegalArgumentException")
    void testInvalidDampingFactor() {
        assertThatThrownBy(() -> new DynamicPricingEngine(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicPricingEngine(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicPricingEngine(1.05)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Registering and querying item initializes prices correctly")
    void testRegisterAndQuery() {
        engine.registerItem("minecraft:diamond", diamondCurve);

        assertThat(engine.hasItem("minecraft:diamond")).isTrue();
        assertThat(engine.hasItem("minecraft:emerald")).isFalse();

        Optional<ShopItemPrice> priceOpt = engine.getPrice("minecraft:diamond");
        assertThat(priceOpt).isPresent();
        ShopItemPrice price = priceOpt.get();
        assertThat(price.itemKey()).isEqualTo("minecraft:diamond");
        assertThat(price.currentPrice()).isEqualTo(100L);
        assertThat(price.supply()).isZero();
        assertThat(price.demand()).isZero();

        assertThat(engine.getUnitPrice("minecraft:diamond")).hasValue(100L);
        assertThat(engine.getUnitPrice("minecraft:emerald")).isEmpty();
    }

    @Test
    @DisplayName("Purchases increase demand and drive price up with damping applied")
    void testRecordPurchase() {
        List<PriceUpdateEvent> events = new ArrayList<>();
        engine.addListener(events::add);
        engine.registerItem("minecraft:diamond", diamondCurve);

        // Buy 100 units: delta = +100, ratio = 1.0, multiplier = 1 + 0.5 = 1.5
        // raw target = 100 * 1.5 = 150
        // damped = 100 + round((150 - 100) * 0.85) = 100 + round(42.5) = 143
        engine.recordPurchase("minecraft:diamond", 100L);

        Optional<ShopItemPrice> priceOpt = engine.getPrice("minecraft:diamond");
        assertThat(priceOpt).isPresent();
        ShopItemPrice price = priceOpt.get();
        assertThat(price.demand()).isEqualTo(100L);
        assertThat(price.supply()).isZero();
        assertThat(price.currentPrice()).isEqualTo(143L);

        assertThat(events).hasSize(1);
        PriceUpdateEvent event = events.get(0);
        assertThat(event.itemKey()).isEqualTo("minecraft:diamond");
        assertThat(event.oldPrice()).isEqualTo(100L);
        assertThat(event.newPrice()).isEqualTo(143L);
        assertThat(event.priceDelta()).isEqualTo(43L);
    }

    @Test
    @DisplayName("Sales increase supply and drive price down within floor bounds")
    void testRecordSaleAndFloorBound() {
        List<PriceUpdateEvent> events = new ArrayList<>();
        engine.addListener(events::add);
        // Using dampingFactor = 1.0 (no damping) to test pure curve ceiling/floor
        DynamicPricingEngine undampedEngine = new DynamicPricingEngine(1.0);
        undampedEngine.addListener(events::add);
        undampedEngine.registerItem("minecraft:diamond", diamondCurve);

        // Sell 500 units: delta = -500, ratio = -5.0, multiplier = 1 - 2.5 = -1.5
        // raw target = -150 -> bounded by floor: 20
        undampedEngine.recordSale("minecraft:diamond", 500L);

        Optional<ShopItemPrice> priceOpt = undampedEngine.getPrice("minecraft:diamond");
        assertThat(priceOpt).isPresent();
        assertThat(priceOpt.get().currentPrice()).isEqualTo(20L);
        assertThat(priceOpt.get().supply()).isEqualTo(500L);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).newPrice()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Extreme demand is capped at ceiling price")
    void testCeilingPriceCap() {
        DynamicPricingEngine undampedEngine = new DynamicPricingEngine(1.0);
        undampedEngine.registerItem("minecraft:diamond", diamondCurve);

        // Buy 2,000 units: delta = +2000, ratio = +20.0, multiplier = 1 + 10.0 = 11.0
        // raw target = 1,100 -> bounded by ceiling: 500
        undampedEngine.recordPurchase("minecraft:diamond", 2000L);

        Optional<ShopItemPrice> priceOpt = undampedEngine.getPrice("minecraft:diamond");
        assertThat(priceOpt).isPresent();
        assertThat(priceOpt.get().currentPrice()).isEqualTo(500L);
    }

    @Test
    @DisplayName("setPriceDirect overrides unit price directly and notifies listeners")
    void testSetPriceDirect() {
        List<PriceUpdateEvent> events = new ArrayList<>();
        engine.addListener(events::add);
        engine.registerItem("minecraft:diamond", diamondCurve);

        engine.setPriceDirect("minecraft:diamond", 175L);

        assertThat(engine.getUnitPrice("minecraft:diamond")).hasValue(175L);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).oldPrice()).isEqualTo(100L);
        assertThat(events.get(0).newPrice()).isEqualTo(175L);
    }

    @Test
    @DisplayName("Throwing listener does not disrupt pricing updates")
    void testFailingListenerTolerance() {
        engine.addListener(e -> {
            throw new RuntimeException("Simulated listener failure");
        });
        List<PriceUpdateEvent> successEvents = new ArrayList<>();
        engine.addListener(successEvents::add);

        engine.registerItem("minecraft:diamond", diamondCurve);
        engine.recordPurchase("minecraft:diamond", 50L);

        assertThat(successEvents).hasSize(1);
    }

    @Test
    @DisplayName("Concurrent purchases and sales execute cleanly without race conditions")
    void testConcurrentTransactions() throws InterruptedException {
        DynamicPricingEngine undamped = new DynamicPricingEngine(1.0);
        undamped.registerItem("minecraft:diamond", diamondCurve);

        int threads = 10;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.execute(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        if (index % 2 == 0) {
                            undamped.recordPurchase("minecraft:diamond", 1L);
                        } else {
                            undamped.recordSale("minecraft:diamond", 1L);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        ShopItemPrice finalPrice = undamped.getPrice("minecraft:diamond").orElseThrow();
        // 5 threads bought 500, 5 threads sold 500
        assertThat(finalPrice.demand()).isEqualTo(500L);
        assertThat(finalPrice.supply()).isEqualTo(500L);
        // When supply == demand, target price is basePrice = 100
        assertThat(finalPrice.currentPrice()).isEqualTo(100L);
    }
}
