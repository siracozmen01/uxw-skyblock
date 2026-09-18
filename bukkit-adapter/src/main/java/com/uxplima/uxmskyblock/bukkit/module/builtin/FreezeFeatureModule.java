package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing the 4-dimensional state model, administrative quarantine lockdown,
 * and visitor eviction.
 */
public final class FreezeFeatureModule extends AbstractFeatureModule {

    private final IslandAdminFreezeService freezeService;

    public FreezeFeatureModule(IslandAdminFreezeService freezeService) {
        super(new ModuleDescriptor(
                "freeze",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-freeze", "island-quarantine"),
                ">=1.0.0",
                false));
        this.freezeService = Objects.requireNonNull(freezeService, "freezeService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandAdminFreezeService.class, freezeService);
    }

    @Override
    protected void onDisable() {
        // No persistent background tasks to cancel
    }
}
