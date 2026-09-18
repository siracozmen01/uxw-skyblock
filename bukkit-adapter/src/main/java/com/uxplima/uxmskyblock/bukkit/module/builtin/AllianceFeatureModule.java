package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in internal feature module managing bilateral island alliances,
 * diplomatic relations, friendly-fire shielding, and alliance chat routing.
 */
public final class AllianceFeatureModule extends AbstractFeatureModule {

    private final IslandAllianceService allianceService;

    public AllianceFeatureModule(IslandAllianceService allianceService) {
        super(new ModuleDescriptor(
                "alliances",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-alliances"),
                ">=1.0.0",
                false));
        this.allianceService = Objects.requireNonNull(allianceService, "allianceService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandAllianceService.class, allianceService);
    }

    @Override
    protected void onDisable() {
        // No asynchronous schedulers or listeners to tear down
    }
}
