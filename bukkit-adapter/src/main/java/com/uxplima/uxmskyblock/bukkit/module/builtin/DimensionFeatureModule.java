package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing multi-dimension architecture, portal routing, and platform schematics (Sections 2.27 & 2.37).
 */
public final class DimensionFeatureModule extends AbstractFeatureModule {

    private final IslandDimensionService dimensionService;

    public DimensionFeatureModule(IslandDimensionService dimensionService) {
        super(new ModuleDescriptor(
                "dimensions",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-dimensions", "multi-dimension", "portal-linkage"),
                ">=1.0.0",
                false));
        this.dimensionService = Objects.requireNonNull(dimensionService, "dimensionService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandDimensionService.class, dimensionService);
    }

    public IslandDimensionService dimensionService() {
        return dimensionService;
    }
}
