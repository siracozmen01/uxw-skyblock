package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing island deletion, safe resetting, and Archimedean spiral slot recycling.
 */
public final class RecycleFeatureModule extends AbstractFeatureModule {

    private final IslandRecycleService recycleService;

    public RecycleFeatureModule(IslandRecycleService recycleService) {
        super(new ModuleDescriptor(
                "recycle",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-recycle", "spiral-slot-pool", "safe-reset", "island-reset", "island-delete"),
                ">=1.0.0",
                false));
        this.recycleService = Objects.requireNonNull(recycleService, "recycleService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandRecycleService.class, recycleService);
    }

    public IslandRecycleService recycleService() {
        return recycleService;
    }
}
