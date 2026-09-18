package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module providing dynamic economy shop pricing curves,
 * market supply/demand tracking, and speculation damping.
 */
public final class ShopFeatureModule extends AbstractFeatureModule {

    private final DynamicPricingEngine dynamicPricingEngine;

    public ShopFeatureModule(DynamicPricingEngine dynamicPricingEngine) {
        super(new ModuleDescriptor(
                "shop", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("dynamic-pricing"), ">=1.0.0", false));
        this.dynamicPricingEngine =
                Objects.requireNonNull(dynamicPricingEngine, "dynamicPricingEngine must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(DynamicPricingEngine.class, dynamicPricingEngine);
    }

    @Override
    protected void onDisable() {
        // No persistent background tasks requiring explicit teardown
    }
}
