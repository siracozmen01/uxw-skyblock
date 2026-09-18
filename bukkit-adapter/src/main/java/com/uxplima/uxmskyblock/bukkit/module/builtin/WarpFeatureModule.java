package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing public island warps, safe teleport anti-trap engine,
 * visitor security, and community warp directory.
 */
public final class WarpFeatureModule extends AbstractFeatureModule {

    private final IslandWarpService warpService;
    private final SafeTeleportEngine safeTeleportEngine;
    private final WarpConfiguration configuration;

    public WarpFeatureModule(
            IslandWarpService warpService, SafeTeleportEngine safeTeleportEngine, WarpConfiguration configuration) {
        super(new ModuleDescriptor(
                "warps",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-warps", "safe-teleport-engine"),
                ">=1.0.0",
                false));
        this.warpService = Objects.requireNonNull(warpService, "warpService must not be null");
        this.safeTeleportEngine = Objects.requireNonNull(safeTeleportEngine, "safeTeleportEngine must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandWarpService.class, warpService);
        context.registerService(SafeTeleportEngine.class, safeTeleportEngine);
    }

    @Override
    protected void onDisable() {
        // No persistent resources to unbind
    }

    public WarpConfiguration configuration() {
        return configuration;
    }
}
