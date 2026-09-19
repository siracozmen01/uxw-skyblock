package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing the island block worth valuation engine, level calculations,
 * and live block/spawner event indexing.
 */
public final class WorthFeatureModule extends AbstractFeatureModule {

    private final IslandWorthService worthService;

    public WorthFeatureModule(IslandWorthService worthService) {
        super(new ModuleDescriptor(
                "worth",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-worth", "island-level", "block-valuation", "levels-engine"),
                ">=1.0.0",
                false));
        this.worthService = Objects.requireNonNull(worthService, "worthService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandWorthService.class, worthService);
    }

    public IslandWorthService worthService() {
        return worthService;
    }
}
