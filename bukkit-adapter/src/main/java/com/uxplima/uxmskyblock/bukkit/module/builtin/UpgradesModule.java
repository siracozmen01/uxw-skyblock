package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;

import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in upgrades module managing island tiers, perks, and crop/spawner rates.
 */
public final class UpgradesModule extends AbstractFeatureModule {

    public UpgradesModule() {
        super(new ModuleDescriptor(
                "upgrades",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-upgrades"),
                ">=1.0.0",
                false));
    }

    @Override
    protected void onEnable(ModuleContext context) {
        // Upgrades initialization
    }
}
